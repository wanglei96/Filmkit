#!/usr/bin/env python3
"""Small contracts for capture association, native thresholds and Lab transport."""
import importlib.util
import json
from pathlib import Path
import tempfile

import numpy as np


def load(filename):
    spec = importlib.util.spec_from_file_location(filename, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def main():
    lamp = load('analyze-lampshade-capture.py')
    rt = load('check-rt-defringe-reference.py')
    assert lamp.clipping_levels([64]*4,1023).tolist()==[1005]*4
    assert lamp.clipping_levels([0,10,20,30],255).tolist()==[250,251,251,251]
    rows = [{'event':'raw.meta','ts':'old'},
            {'event':'edge_dump.stage','kv':{'path':'/edge_dump_1_D4'}},
            {'event':'raw.meta','ts':'correct'},
            {'event':'edge_dump.stage','kv':{'path':'/edge_dump_2_D4'}},
            {'event':'raw.render'},
            {'event':'raw.meta','ts':'next'}]
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder)/'capture.log'
        path.write_text('not json\n'+'\n'.join(map(json.dumps,rows)))
        assert lamp.capture_events(path,'2')==rows[2:5]
        assert lamp.capture_events(path,'1')==rows[:2]
        metadata = {'complete':True,'token':'2','stage':'test','orientation':'gl_bottom_up',
                    'type':'float16','h':2,'w':4,'components':1,'raw_path':'/test.rgba16f'}
        (Path(folder)/'edge_dump_2_test.json').write_text(json.dumps(metadata))
        data_path = Path(folder)/'test.rgba16f'
        data_path.write_bytes(np.zeros((2,4,1),'<f2').tobytes())
        data,_ = lamp.stage(Path(folder),'2','test')
        assert not data.flags.writeable
        del data
        for bad_length in (14,18):
            data_path.write_bytes(bytes(bad_length))
            try:
                lamp.stage(Path(folder),'2','test')
            except AssertionError:
                pass
            else:
                raise AssertionError('short/extended stage file accepted')
    cpp = 'void test() { /* } */ const char* s="{"; if(true) {} } trailing'
    assert rt.block(cpp,'void test()')==cpp[:-9]
    rgb = np.array([[[0,0,0],[1,1,1],[10,2,1],[.8,.2,.65],[-.01,.4,.1]]], 'f4')
    lab = rt.to_lab(rgb)
    assert np.isfinite(lab).all()
    assert np.max(np.abs(rt.from_lab(lab)-rgb))<3e-6
    assert np.linalg.norm(lab[0,1,1:])<.01
    print('PASS capture association, exact/read-only stage storage, inclusive clipping, source extraction, HDR Lab roundtrip')


if __name__=='__main__':
    main()
