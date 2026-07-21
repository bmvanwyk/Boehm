#!/bin/bash
# Mock Tulip CLI: accepts --config <file>, writes sample JSON to output_filename.
set -e

CONFIG_FILE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --config) CONFIG_FILE="$2"; shift 2 ;;
        *) shift ;;
    esac
done

if [ -z "$CONFIG_FILE" ]; then
    echo "mock-tulip: missing --config" >&2
    exit 1
fi

exec python3 -c "
import json, os, sys

with open('$CONFIG_FILE') as f:
    cfg = json.load(f)

output_file = cfg['actions']['output_filename']
os.makedirs(os.path.dirname(output_file) or '.', exist_ok=True)

result = {
    'version': '2.3.4',
    'timestamp': '2026-07-21_10:00:00',
    'java': {
        'jvm.system.properties': {
            'java.vendor': 'Eclipse Adoptium',
            'java.version': '21.0.7',
            'os.name': 'Linux',
            'os.arch': 'amd64'
        },
        'jvm.runtime.options': ['-Xms512m', '-Xmx512m']
    },
    'config': cfg,
    'results': [{
        'context_name': 'default',
        'context_id': 0,
        'bm_name': 'boehm-benchmark',
        'bm_id': 1,
        'row_id': 0,
        'num_users': 10,
        'num_threads': 2,
        'queue_length': 100,
        'workflow_name': '',
        'test_begin': '2026-07-21_10:00:00',
        'test_end': '2026-07-21_10:00:05',
        'duration': 5,
        'num_actions': 500,
        'num_failed': 0,
        'avg_aps': 100.0,
        'aps_target_rate': 100.0,
        'avg_rt': 8500000.0,
        'sd_rt': 5000000.0,
        'min_rt': 2100000.0,
        'max_rt': 210000000.0,
        'percentiles_rt': {
            '50.0': 8500000.0,
            '75.0': 15000000.0,
            '90.0': 22300000.0,
            '95.0': 35000000.0,
            '99.0': 68100000.0
        },
        'hdr_histogram_rt': 'HIST',
        'user_actions': {}
    }]
}

with open(output_file, 'w') as f:
    json.dump(result, f, indent=2)

sys.exit(0)
"
