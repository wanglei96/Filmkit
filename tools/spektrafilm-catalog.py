"""Export pinned spectral recipes, sharing one Hanatos response per film stock."""
import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys

import importlib.util


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--refresh', action='store_true')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('spektrafilm-render-fixture.py'))
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    profiles = [json.loads(p.read_text())['info'] for p in
                sorted((args.upstream / 'src/spektrafilm/data/profiles').glob('*.json'))]
    films = [p for p in profiles if p['stage'] == 'filming']
    papers = [p for p in profiles if p['stage'] == 'printing']
    entries = []
    for film in films:
        direct = film['type'] == 'positive'
        response = args.output / (film['stock'] + '.response')
        for paper in ([next(p for p in papers if p['stock'] == 'kodak_portra_endura')] if direct else papers):
            key = film['stock'] + '--' + ('direct' if direct else paper['stock'])
            path = args.output / (key + '.skp')
            if args.refresh and path.exists():
                path.unlink()
            if not path.exists():
                sys.argv = [str(spec.origin), str(args.upstream), '-', str(path), '--profile-only',
                            '--film', film['stock'], '--paper', paper['stock'], '--response-file', str(response)]
                if direct:
                    sys.argv.append('--direct')
                with contextlib.redirect_stdout(io.StringIO()):
                    fixture.main()
            entries.append(dict(id=key, film=film['stock'], filmName=film['name'],
                                positive=direct, paper='' if direct else paper['stock'],
                                paperName='' if direct else paper['name'],
                                asset=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                                response=response.name, responseSha256=hashlib.sha256(response.read_bytes()).hexdigest()))
            print(key, flush=True)
    manifest = dict(schema=3, upstream='3bb2c2d2801ff68b92019cf1dbcbb133d60832bc',
                    author='Andrea Volpato', license='CC BY-SA 4.0',
                    modification='FP64 runtime recipe export; shared per-stock Hanatos response; no image pixels',
                    profiles=entries)
    (args.output / 'catalog.json').write_text(json.dumps(manifest, ensure_ascii=True, indent=2) + '\n')


if __name__ == '__main__':
    main()
