// Dictionary from the MIT-licensed unique-names-generator package. Runtime uses
// SecureRandom over the nonhuman color/animal dictionaries; never person names.
const { colors, animals } = require('unique-names-generator');
const fs = require('node:fs');
fs.writeFileSync(__dirname + '/../plugin/src/main/res/raw/member_names.json',
  JSON.stringify({colors, animals}, null, 2) + '\n');
