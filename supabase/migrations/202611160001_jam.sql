-- Run with the Supabase SQL editor. Enable anonymous sign-ins in Auth first.
create schema if not exists jam_private;
revoke all on schema jam_private from public, anon, authenticated;
create table jam_private.rooms (
  id uuid primary key default gen_random_uuid(),
  code text unique not null check (code ~ '^[a-z0-9]{4}$'),
  leader uuid not null,
  controls boolean not null default false,
  alone_since timestamptz default now(),
  created_at timestamptz not null default now()
);
create table jam_private.members (
  room_id uuid not null references jam_private.rooms on delete cascade,
  user_id uuid not null,
  name text not null check (length(name) between 1 and 60),
  seen_at timestamptz not null default now(),
  primary key(room_id, user_id),
  unique(user_id)
);
create table jam_private.attempts (user_id uuid primary key, window_start timestamptz not null default now(), count integer not null default 0);
alter table jam_private.rooms enable row level security;
alter table jam_private.members enable row level security;
alter table jam_private.attempts enable row level security;

create or replace function jam_private.member_of(topic text) returns boolean
language sql stable security definer set search_path = '' as $$
 select exists(select 1 from jam_private.members m join jam_private.rooms r on r.id = m.room_id
 where m.user_id = auth.uid() and m.seen_at > now() - interval '45 seconds'
 and topic = 'jam:' || r.id::text);
$$;
grant usage on schema jam_private to authenticated;
grant execute on function jam_private.member_of(text) to authenticated;
-- Members can receive server-authored broadcasts. Clients cannot broadcast directly.
create policy jam_receive on realtime.messages for select to authenticated
using (extension = 'broadcast' and jam_private.member_of(realtime.topic()));

create or replace function jam_private.snapshot(room uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
 select jsonb_build_object('id', r.id, 'code', r.code, 'leader', r.leader, 'controls', r.controls,
 'aloneSince', extract(epoch from r.alone_since) * 1000,
 'members', coalesce((select jsonb_agg(jsonb_build_object('id',m.user_id,'name',m.name) order by m.user_id)
 from jam_private.members m where m.room_id = r.id), '[]'::jsonb)) from jam_private.rooms r where r.id = room;
$$;
create or replace function jam_private.prune(room uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare r jam_private.rooms;
begin
 select * into r from jam_private.rooms where id = room for update;
 if not found then return; end if;
 delete from jam_private.members where room_id = room and seen_at < now() - interval '45 seconds';
 if not exists(select 1 from jam_private.members where room_id = room and user_id = r.leader)
 or (r.alone_since is not null and r.alone_since < now() - interval '5 minutes') then
  perform realtime.send(jsonb_build_object('reason','ended'), 'disband', 'jam:' || room::text, true);
  delete from jam_private.rooms where id = room;
 else
  update jam_private.rooms set alone_since = case when
   (select count(*) from jam_private.members where room_id = room) = 1 then coalesce(alone_since,now()) else null end where id = room;
 end if;
end;
$$;

create or replace function public.jam_enter(invite text, member_name text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); room uuid; candidate text; tries integer := 0; a jam_private.attempts; alphabet text := 'abcdefghijklmnopqrstuvwxyz0123456789';
begin
 if uid is null then return jsonb_build_object('error','Sign in first'); end if;
 if length(member_name) not between 1 and 60 then return jsonb_build_object('error','Invalid member name'); end if;
 insert into jam_private.attempts(user_id) values(uid) on conflict do nothing;
 select * into a from jam_private.attempts where user_id = uid for update;
 if a.window_start < now() - interval '1 minute' then
  update jam_private.attempts set window_start = now(), count = 1 where user_id = uid;
 elsif a.count >= 10 then return jsonb_build_object('error','Wait a minute before trying again');
 else update jam_private.attempts set count = count + 1 where user_id = uid; end if;
 select room_id into room from jam_private.members where user_id = uid;
 if room is not null then perform jam_private.prune(room); end if;
 room := null;
 if exists(select 1 from jam_private.members where user_id = uid) then
  return jsonb_build_object('error','Leave your current Jam first');
 end if;
 if invite = '' then
  loop
   candidate := '';
   for i in 1..4 loop candidate := candidate || substr(alphabet, 1 + (get_byte(extensions.gen_random_bytes(1),0) % 36), 1); end loop;
   begin
    insert into jam_private.rooms(code,leader) values(candidate,uid) returning id into room;
    exit;
   exception when unique_violation then tries := tries + 1; if tries >= 20 then return jsonb_build_object('error','Try creating again'); end if;
   end;
  end loop;
 else
  if invite !~ '^[a-z0-9]{4}$' then return jsonb_build_object('error','Use a four-character code'); end if;
  select id into room from jam_private.rooms where code = invite for update;
  if room is null then return jsonb_build_object('error','That Jam has ended or does not exist'); end if;
  perform jam_private.prune(room);
  if not exists(select 1 from jam_private.rooms where id = room) then return jsonb_build_object('error','That Jam has ended'); end if;
  if (select count(*) from jam_private.members where room_id = room) >= 4 then return jsonb_build_object('error','This Jam is full'); end if;
 end if;
 insert into jam_private.members(room_id,user_id,name) values(room,uid,member_name);
 if invite <> '' then update jam_private.rooms set alone_since = null where id = room; end if;
 perform realtime.send(jsonb_build_object('sender',uid), 'membership', 'jam:' || room::text,true);
 return jam_private.snapshot(room);
exception when unique_violation then return jsonb_build_object('error','You are already in a Jam');
end;
$$;
create or replace function public.jam_touch(room uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
 perform jam_private.prune(room);
 update jam_private.members set seen_at = now() where room_id = room and user_id = auth.uid();
 if not found then return jsonb_build_object('error','Your Jam ended'); end if;
 return jam_private.snapshot(room);
end;
$$;
create or replace function public.jam_leave(room uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare leader_id uuid;
begin
 select leader into leader_id from jam_private.rooms where id = room for update;
 if not exists(select 1 from jam_private.members where room_id = room and user_id = auth.uid()) then return '{}'::jsonb; end if;
 if leader_id = auth.uid() then
  perform realtime.send('{}'::jsonb,'disband','jam:' || room::text,true);
  delete from jam_private.rooms where id = room;
 else
  delete from jam_private.members where room_id = room and user_id = auth.uid();
  update jam_private.rooms set alone_since = case when (select count(*) from jam_private.members where room_id=room)=1 then now() else null end where id=room;
  perform realtime.send('{}'::jsonb,'membership','jam:' || room::text,true);
 end if;
 return '{}'::jsonb;
end;
$$;
create or replace function public.jam_controls(room uuid, enabled boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
 update jam_private.rooms set controls=enabled where id=room and leader=auth.uid();
 if not found then return jsonb_build_object('error','Only the leader can change group controls'); end if;
 perform realtime.send('{}'::jsonb,'membership','jam:' || room::text,true);
 return jam_private.snapshot(room);
end;
$$;
create or replace function public.jam_signal(room uuid, target uuid, event text, payload jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare r jam_private.rooms;
begin
 select * into r from jam_private.rooms where id=room;
 if not found or not exists(select 1 from jam_private.members where room_id=room and user_id=auth.uid() and seen_at > now()-interval '45 seconds') then
  return jsonb_build_object('error','Your Jam ended');
 end if;
 if target is not null and not exists(select 1 from jam_private.members where room_id=room and user_id=target) then return jsonb_build_object('error','Member left'); end if;
 if event not in ('offer','answer','ice','ready','clock_ping','clock_pong','sync','request_sync','control','track','transfer_request')
 or octet_length(payload::text) > 60000 then return jsonb_build_object('error','Invalid message'); end if;
 if event in ('sync','track') and r.leader <> auth.uid() then return jsonb_build_object('error','Only the leader can publish playback'); end if;
 if event='control' and auth.uid() <> r.leader and not r.controls then return jsonb_build_object('error','Group controls are disabled'); end if;
 -- Peers can signal only the leader; the star topology never forwards peer audio.
 if r.leader <> auth.uid() and (target is null or target <> r.leader) then return jsonb_build_object('error','Send to the leader'); end if;
 perform realtime.send(jsonb_build_object('sender',auth.uid(),'target',target,'payload',payload),event,'jam:' || room::text,true);
 return '{}'::jsonb;
end;
$$;
-- A cron sweep reclaims abandoned rooms even when every client has disappeared.
create or replace function public.jam_sweep() returns void
language plpgsql security definer set search_path = '' as $$
declare room uuid;
begin
 for room in select id from jam_private.rooms loop perform jam_private.prune(room); end loop;
 delete from jam_private.attempts where window_start < now() - interval '1 day';
end;
$$;
revoke all on all functions in schema jam_private from public, anon;
revoke all on function public.jam_enter(text,text), public.jam_touch(uuid), public.jam_leave(uuid), public.jam_controls(uuid,boolean), public.jam_signal(uuid,uuid,text,jsonb), public.jam_sweep() from public, anon;
grant execute on function public.jam_enter(text,text), public.jam_touch(uuid), public.jam_leave(uuid), public.jam_controls(uuid,boolean), public.jam_signal(uuid,uuid,text,jsonb) to authenticated;
grant execute on function jam_private.member_of(text) to authenticated;
grant execute on function public.jam_sweep() to service_role;
-- Enable pg_cron in Dashboard > Database > Extensions, then run:
-- select cron.schedule('jam-cleanup', '* * * * *', 'select public.jam_sweep()');
