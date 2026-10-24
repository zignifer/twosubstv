"""Capture one selected authorized TV; remove the device driver's non-PNG stdout preamble."""
import argparse
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
result = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'screencap', '-p'], capture_output=True, check=True)
start = result.stdout.find(b'\x89PNG\r\n\x1a\n')
if start < 0:
    raise RuntimeError('Device did not return a PNG screenshot')
target = Path(args.output)
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(result.stdout[start:])
print(str(target.resolve()))
