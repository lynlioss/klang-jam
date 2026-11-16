"""AGP can return success after instrumentation crashes; inspect JUnit results."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path('plugin/build/outputs/androidTest-results/connected/release')
reports = list(root.rglob('TEST-*.xml'))
assert reports, 'No Android instrumentation test results'
cases = []
for report in reports:
    suite = ET.parse(report).getroot()
    assert not int(suite.get('failures', 0)), report.read_text()
    assert not int(suite.get('errors', 0)), report.read_text()
    cases.extend(suite.iter('testcase'))
names = {case.get('name') for case in cases}
expected = {
    'nativeDataChannelStartupWorksWithoutMicrophonePermission',
    'nativeJniEntryPointsSurviveReleaseOptimization',
    'soloRoomCanCloseBeforeNativeStartup',
    'twoPeersNegotiateAndTransferChunksWithoutTurnThenLeaveAndReconnect',
}
assert expected <= names, f'Instrumentation did not execute required tests: {expected - names}'
for case in cases:
    assert case.find('failure') is None and case.find('error') is None and case.find('skipped') is None, ET.tostring(case)
print(f'All {len(cases)} Android tests completed successfully')
