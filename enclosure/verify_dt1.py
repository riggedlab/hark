"""复核 DT-1 重建、独立运行、差异报错和成品防覆盖；不改动基准文件。

blender -b --factory-startup --python enclosure/verify_dt1.py -- [--out DIR]
"""
from pathlib import Path
import argparse
import json
import runpy
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import traceback

import bpy


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, help='复核日志和产物目录；默认新建临时目录')
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    here = Path(__file__).resolve().parent
    out = args.out.expanduser().resolve() if args.out else Path(tempfile.mkdtemp(prefix='hark-dt1-verify-'))
    if out.is_relative_to(here.parent):
        raise ValueError('复核产物请放到仓库以外的目录。')
    out.mkdir(parents=True, exist_ok=True)
    script = here/'build_dt1.py'
    blender = bpy.app.binary_path
    print(f'VERIFY OUTPUT {out}', flush=True)

    def run(name, command, expected_code=0, marker=None):
        print('COMMAND '+shlex.join(command), flush=True)
        # 沿用调用环境，不注入环境变量；每次启动全新 Blender 场景。
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (out/f'{name}.log').write_text(completed.stdout, encoding='utf-8')
        print(completed.stdout, end='', flush=True)
        assert completed.returncode == expected_code, (name, completed.returncode, expected_code)
        if marker:
            assert marker in completed.stdout, (name, 'missing success marker', marker)
        print(f'VERIFY {name}: OK exit_code={completed.returncode}', flush=True)
        return completed.stdout

    def build_command(path, *arguments):
        return [blender, '-b', '--factory-startup', '--python', str(path), '--', *arguments]

    for variant in ('plain', 'carved'):
        run(variant, build_command(script, '--variant', variant, '--check', '--blend', '--out', str(out/'generated')),
            marker='DT1_BUILD_OK')
        blend = out/'generated'/f'dt1-{variant}'/f'hark-dt1-{variant}.blend'
        checks = json.loads((blend.parent/'checks.json').read_text(encoding='utf-8'))
        expected_faces = {name: stats['faces'] for name, stats in checks['parts'].items()}
        expression = (
            'import bpy; '
            'actual = {o.name: len(o.data.polygons) for o in bpy.context.scene.objects if o.type == "MESH"}; '
            f'assert actual == {expected_faces!r}, actual; '
            'print("BLEND_REOPEN_OK", actual)'
        )
        run(f'{variant}-blend', [blender, '-b', '--factory-startup', str(blend), '--python-expr', expression],
            marker='BLEND_REOPEN_OK')

    # 只复制这一个文件，既没有基准 STL，也没有设计文档或其他建模模块。
    standalone = out/'standalone'/'build_dt1.py'
    standalone.parent.mkdir(exist_ok=True)
    shutil.copyfile(script, standalone)
    run('standalone-default-output', build_command(standalone, '--variant', 'all'), marker='DT1_BUILD_OK')

    # 故意用重复的底面三角面替换另一底面三角面：面数、包围盒、体积均不变。
    # 这能证明 --check 真在比三角面集合，而非只比这三个汇总数字。
    fixture = out/'different-reference'
    fixture.mkdir(exist_ok=True)
    shutil.copyfile(script, fixture/'build_dt1.py')
    refs = fixture/'dt1-plain'/'parts'
    refs.mkdir(parents=True, exist_ok=True)
    for name in ('case-front', 'case-back'):
        shutil.copyfile(here/'dt1-plain'/'parts'/f'{name}.stl', refs/f'{name}.stl')
    path = refs/'case-front.stl'
    original = path.read_bytes()
    data = bytearray(original)
    count = struct.unpack_from('<I', data, 80)[0]
    floor = [i for i in range(count)
             if all(z == 0 for z in struct.unpack_from('<12fH', data, 84+50*i)[5:12:3])]
    assert len(floor) >= 2, 'need two floor triangles for the negative check'
    source, target = (84+50*i for i in floor[:2])
    data[target:target+50] = original[source:source+50]
    path.write_bytes(data)
    helpers = runpy.run_path(str(script))
    read, measure = helpers['read_stl'], helpers['stl_measurements']
    before = measure(*read(here/'dt1-plain'/'parts'/'case-front.stl'))
    after = measure(*read(path))
    assert before == after, ('negative fixture changed summary measurements', before, after)
    log = run('different-reference', build_command(fixture/'build_dt1.py', '--variant', 'plain', '--check',
                                                  '--out', str(out/'negative-generated')), expected_code=1)
    assert 'CHECK plain/case-front: DIFFERENT' in log and 'CHECK plain/case-back: OK' in log
    assert 'max_bounds_delta_mm=0 volume_delta_mm3=0' in log
    log = run('protect-repository', build_command(script, '--variant', 'all', '--out', str(here)), expected_code=1)
    assert '输出会覆盖仓库成品' in log
    print('DT1_VERIFY_OK', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
