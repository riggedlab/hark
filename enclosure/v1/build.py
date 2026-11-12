"""Rebuild Diting v1 in isolated Blender: meshes, STL, native K3 3MF, QA, renders.

Run: /Applications/Blender.app/Contents/MacOS/Blender -b --factory-startup --python enclosure/v1/build.py
Millimetres throughout. No network, printer control, packages or environment overrides.
"""
from pathlib import Path
import hashlib
import itertools
import json
import math
import os
import struct
import uuid
import zipfile
import xml.etree.ElementTree as ET

import bpy
import bmesh
from mathutils import Vector
from mathutils.bvhtree import BVHTree

# All design dimensions are in assembly coordinates, mm; DESIGN.md is read-only.
HERE = Path(__file__).resolve().parent
DESIGN = HERE.parent / 'DESIGN.md'
TEMPLATE = Path(os.environ['SLICER_TEMPLATE_3MF']) if os.environ.get('SLICER_TEMPLATE_3MF') else None
CAV_W, CAV_L, CAV_H, CAV_R = 20.2, 22.0, 12.0, .5
WALL_SIDE, WALL_USB, FRONT, LID_T, OUT_R = 1.2, 1.8, 1.2, 2.0, 2.0
BODY_X, BODY_Y, BODY_Z = (-11.3, 11.3), (-1.2, 23.8), (-15.2, 0)
FRONT_CHAMFER, REAR_CHAMFER = .5, .4
USB_X, USB_Z = (-4.7, 4.7), (-15.2, -1.4)
MIC_XY, MIC_OD, MIC_ID, MIC_TIP = (0, 5.65), 2.8, 1.5, -2.4
PIN_X, PIN_Y, PIN_D = (-4.7, 4.5), 5.65, 1.6
LED_X, LED_Y, LED_D, LED_SKIN = (-6.3, 6.3), 20.85, 2.6, .36
GROOVE_Y, SNAP_Y, SNAP_Z = (4.5, 17.5), (5, 17), -14.2
SNAP_GROOVE_DEPTH, SNAP_GROOVE_H = .45, 1.2
SNAP_PROJECTION, SNAP_H = .35, 1.0
EAR_W, EAR_CY, EAR_TOP, EAR_R, EAR_Z, EAR_HOLE = 7, -5, -.5, 3.5, (-3, 0), 2.6
BACK_X, BACK_Y, BACK_Z, BACK_R, BACK_CHAMFER = (-10, 10), (.1, 21.9), (-15.2, -13.2), .4, .3
TONGUE_X, TONGUE_Y, TONGUE_Z = (-4.6, 4.6), (21.9, 23.8), (-15.2, -5.16)
SLOT_RIGHT, SLOT_LEFT_MOUTH, SLOT_LEFT_FLOOR = 3, -3, -3.8
SLOT_Z, SLOT_Y = (-15.2, -14.2), (2, 23.8)
CLIP_X, CLIP_BASE_Y, CLIP_BASE_Z = (-6, 6), (1, 23), (-16.6, -15.2)
SPRING_Y, SPRING_Z = (2.8, 20), (-18.8, -17.4)
BEND_CYZ, BEND_RO, BEND_RI = (2.8, -17), 1.8, .4
BUMP_Y, BUMP_HALF_L, BUMP_H = 17, .6, .6  # unspecified width: symmetric 45 degree rib
LIP_END_Y, LIP_FLARE = 22.5, 1.0
KEY_ROOT_LEFT, KEY_TOP_LEFT, KEY_RIGHT = -2.85, -3.65, 2.85
KEY_Z, KEY_Y = (-15.2, -14.3), (2.1, 23)
BOARD_X, BOARD_Y, BOARD_Z = (-9.8, 9.8), (.25, 21.75), (-13.2, -2.6)
USB_BLOCK_X, USB_BLOCK_Y, USB_BLOCK_Z = (-4.47, 4.47), (21.75, 23.75), (-4.86, -1.6)
SEGMENTS, ROUND_QUARTER, EXPORT_DIGITS = 96, 24, 3
PROCESS = {'layer_height': '0.16', 'initial_layer_print_height': '0.16',
           'wall_loops': '3', 'sparse_infill_density': '100%',
           'enable_support': '0', 'brim_type': 'no_brim', 'brim_width': '0',
           'skirt_loops': '0', 'enable_prime_tower': '0'}
PLACEMENTS = {'case-front': (94, 100), 'case-back': (122, 100), 'clip': (144, 100)}
COLORS = {'case-front': (.19, .48, .65, 1), 'case-back': (.91, .64, .23, 1),
          'clip': (.24, .67, .48, 1)}
EXPECTED = {'case-front': [22.6, 32.3, 15.2], 'case-back': [20.7, 23.7, 10.04],
            'clip': [12, 22, 5.5]}
EXPECTED_BOUNDS = {'case-front': [(-11.3, 11.3), (-8.5, 23.8), (-15.2, 0)],
                   'case-back': [(-10.35, 10.35), (.1, 23.8), (-15.2, -5.16)],
                   'clip': [(-6, 6), (1, 23), (-19.8, -14.3)]}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def png_pixel_sha(path):
    # Blender writes Date/RenderTime text chunks even without visible stamps.
    # IHDR+IDAT identity proves the viewed image is unchanged across rebuilds.
    data = path.read_bytes(); offset = 8; pixels = bytearray()
    while offset < len(data):
        size = struct.unpack_from('>I', data, offset)[0]
        kind, payload = data[offset+4:offset+8], data[offset+8:offset+8+size]
        if kind in (b'IHDR', b'IDAT'):
            pixels.extend(kind); pixels.extend(payload)
        offset += size+12
    return hashlib.sha256(pixels).hexdigest()


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def obj_mesh(name, vertices, faces):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    return obj


def loft(name, rings):
    n = len(rings[0][1])
    vertices = [(x, y, z) for z, xy in rings for x, y in xy]
    faces = [tuple(reversed(range(n))), tuple(range((len(rings)-1)*n, len(rings)*n))]
    for k in range(len(rings)-1):
        for j in range(n):
            faces.append((k*n+j, k*n+(j+1)%n, (k+1)*n+(j+1)%n, (k+1)*n+j))
    return obj_mesh(name, vertices, faces)


def prism(name, polygon, lo, hi, axis='Z'):
    obj = loft(name, [(lo, polygon), (hi, polygon)])
    if axis != 'Z':
        for v in obj.data.vertices:
            u, w, t = v.co
            v.co = (t, u, w) if axis == 'X' else (u, t, w)
        bm = bmesh.new(); bm.from_mesh(obj.data)
        bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
        bm.to_mesh(obj.data); bm.free()
    return obj


def box(name, xr, yr, zr):
    return prism(name, [(xr[0], yr[0]), (xr[1], yr[0]),
                        (xr[1], yr[1]), (xr[0], yr[1])], *zr)


def circle(cx, cy, r):
    return [(cx+r*math.cos(2*math.pi*i/SEGMENTS),
             cy+r*math.sin(2*math.pi*i/SEGMENTS)) for i in range(SEGMENTS)]


def rr(xr, yr, radius, inset=0):
    x0, x1 = xr[0]+inset, xr[1]-inset
    y0, y1 = yr[0]+inset, yr[1]-inset
    r = radius-inset
    points = []
    for cx, cy, start in [(x1-r, y1-r, 0), (x0+r, y1-r, 90),
                           (x0+r, y0+r, 180), (x1-r, y0+r, 270)]:
        for j in range(ROUND_QUARTER+1):
            a = math.radians(start+j*90/ROUND_QUARTER)
            points.append((cx+r*math.cos(a), cy+r*math.sin(a)))
    return points


def chamfered_plate(name, xr, yr, zr, radius, bottom, top):
    return loft(name, [(zr[0], rr(xr, yr, radius, bottom)),
                       (zr[0]+bottom, rr(xr, yr, radius)),
                       (zr[1]-top, rr(xr, yr, radius)),
                       (zr[1], rr(xr, yr, radius, top))])


def boolean(a, b, operation):
    bpy.context.view_layer.objects.active = a
    mod = a.modifiers.new(operation, 'BOOLEAN')
    mod.operation, mod.solver, mod.object = operation, 'MANIFOLD', b
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(b, do_unlink=True)
    return a


def union(a, b):
    return boolean(a, b, 'UNION')


def subtract(a, b):
    return boolean(a, b, 'DIFFERENCE')


def clone(obj, name):
    other = obj.copy(); other.data = obj.data.copy(); other.name = name
    bpy.context.collection.objects.link(other)
    return other


def mesh_stats(obj):
    bm = bmesh.new(); bm.from_mesh(obj.data)
    pending, components = set(bm.verts), 0
    while pending:
        components += 1
        stack = [pending.pop()]
        while stack:
            for edge in stack.pop().link_edges:
                for vertex in edge.verts:
                    if vertex in pending:
                        pending.remove(vertex); stack.append(vertex)
    points = [v.co for v in bm.verts]
    result = {'bounds_mm': [[min(v[i] for v in points), max(v[i] for v in points)] for i in range(3)],
              'dimensions_mm': [max(v[i] for v in points)-min(v[i] for v in points) for i in range(3)],
              'volume_mm3': bm.calc_volume(signed=True),
              'non_manifold_edges': sum(not e.is_manifold for e in bm.edges),
              'connected_components': components,
              'degenerate_faces': sum(f.calc_area() < 1e-10 for f in bm.faces),
              'vertices': len(bm.verts), 'faces': len(bm.faces)}
    bm.free()
    return result


def intersection(a, b):
    probe = clone(a, 'QA intersection')
    tool = clone(b, 'QA cutter')
    boolean(probe, tool, 'INTERSECT')
    bm = bmesh.new(); bm.from_mesh(probe.data)
    volume = abs(bm.calc_volume(signed=True)); bm.free()
    bpy.data.objects.remove(probe, do_unlink=True)
    return volume


def distance(a, b):
    # Exact triangle surface distances in both directions at every mesh vertex.
    # For the parallel/planar fit faces here this includes the minimum witnesses.
    trees = [BVHTree.FromPolygons([v.co for v in ob.data.vertices],
                                 [tuple(p.vertices) for p in ob.data.polygons]) for ob in (a, b)]
    best, witness = float('inf'), None
    for ob, tree in ((a, trees[1]), (b, trees[0])):
        for v in ob.data.vertices:
            hit = tree.find_nearest(v.co)
            if hit and hit[3] < best:
                best, witness = hit[3], [list(v.co), list(hit[0])]
    return {'surface_distance_mm': best, 'witness_points_mm': witness,
            'method': 'bidirectional vertex-to-triangle; analytic parallel-face checks also recorded'}


def ray_coordinate(obj, origin, direction, axis):
    tree = BVHTree.FromPolygons([v.co for v in obj.data.vertices],
                               [tuple(p.vertices) for p in obj.data.polygons])
    point, normal, face, distance = tree.ray_cast(Vector(origin), Vector(direction))
    assert point is not None, (obj.name, origin, direction)
    return point[axis]


def triangulate_quantize(obj):
    for v in obj.data.vertices:
        v.co = tuple(round(c, EXPORT_DIGITS) for c in v.co)
    bm = bmesh.new(); bm.from_mesh(obj.data)
    # Export vertex welding only: coincident vertices, no hole/shape repair.
    bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=1e-6)
    bmesh.ops.triangulate(bm, faces=list(bm.faces))
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(obj.data); bm.free()


def print_vertices(obj, name):
    out = []
    for vertex in obj.data.vertices:
        x, y, z = vertex.co
        if name == 'case-front': p = (-x, y+8.5, -z)
        elif name == 'case-back': p = (x, y-.1, z+15.2)
        else: p = (z+19.8, y-1, 6-x)
        out.append(tuple(round(c, EXPORT_DIGITS) for c in p))
    return out


def write_stl(path, vertices, triangles):
    with path.open('wb') as f:
        f.write(b'Diting v1; millimeters; exact axis permutation'.ljust(80, b'\0'))
        f.write(struct.pack('<I', len(triangles)))
        for tri in triangles:
            a, b, c = [Vector(vertices[i]) for i in tri]
            normal = (b-a).cross(c-a).normalized()
            f.write(struct.pack('<12fH', *normal, *a, *b, *c, 0))


def read_stl(path):
    data = path.read_bytes(); count = struct.unpack_from('<I', data, 80)[0]
    assert len(data) == 84+50*count
    vertices, triangles, index = [], [], {}
    for i in range(count):
        values = struct.unpack_from('<12fH', data, 84+50*i)
        tri = []
        for j in range(3):
            p = tuple(values[3+3*j:6+3*j])
            if p not in index:
                index[p] = len(vertices); vertices.append(p)
            tri.append(index[p])
        triangles.append(tri)
    return vertices, triangles


def native_project(parts):
    ns = 'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'
    prod = 'http://schemas.microsoft.com/3dmanufacturing/production/2015/06'
    ET.register_namespace('', ns); ET.register_namespace('p', prod)
    def sub(parent, name, attrs=None):
        return ET.SubElement(parent, '{'+ns+'}'+name, attrs or {})
    def xml(root):
        return ET.tostring(root, encoding='utf-8', xml_declaration=True)
    def transform(x=0, y=0):
        return f'1 0 0 0 1 0 0 0 1 {x:.3f} {y:.3f} 0'
    def uid(name):
        return str(uuid.uuid5(uuid.NAMESPACE_URL, 'diting-v1/'+name))
    with zipfile.ZipFile(TEMPLATE) as source:
        assert source.testzip() is None
        members = {k: source.read(k) for k in ['[Content_Types].xml', '_rels/.rels',
                   '3D/_rels/3dmodel.model.rels', 'Metadata/creality.config']}
        settings = json.loads(source.read('Metadata/project_settings.config'))
    settings.update(PROCESS)
    dirty = settings['different_settings_to_system']
    settings['different_settings_to_system'] = [';'.join(sorted(set(dirty[0].split(';')) | set(PROCESS))),
                                                dirty[1], dirty[-1]]
    settings['print_settings_id'] = 'Diting v1 0.16mm 3 walls solid no support no brim'
    main = ET.Element('{'+ns+'}model', {'unit': 'millimeter', 'requiredextensions': 'p'})
    sub(main, 'metadata', {'name': 'Application'}).text = 'Creality_Print V7.3.1.6378 Release'
    sub(main, 'metadata', {'name': 'BambuStudio:3mfVersion'}).text = '1'
    sub(main, 'metadata', {'name': 'Title'}).text = 'diting-case-v1-K3'
    resources = sub(main, 'resources'); build = sub(main, 'build', {'{'+prod+'}UUID': uid('build')})
    partmodel = ET.Element('{'+ns+'}model', {'unit': 'millimeter'})
    pr = sub(partmodel, 'resources')
    config = ET.Element('config'); plate = ET.SubElement(config, 'plate')
    for k, v in [('plater_id', '1'), ('plater_name', 'diting-case-v1-K3'), ('locked', 'false')]:
        ET.SubElement(plate, 'metadata', {'key': k, 'value': v})
    assembly = ET.SubElement(config, 'assemble')
    for i, (name, (vertices, triangles)) in enumerate(parts.items(), 1):
        oid, pid = str(100+i), str(i)
        pobj = sub(pr, 'object', {'id': pid, 'type': 'model', 'name': name})
        mesh = sub(pobj, 'mesh'); vs = sub(mesh, 'vertices'); ts = sub(mesh, 'triangles')
        for p in vertices:
            sub(vs, 'vertex', {axis: f'{p[j]:.3f}' for j, axis in enumerate('xyz')})
        for t in triangles:
            sub(ts, 'triangle', {f'v{j+1}': str(t[j]) for j in range(3)})
        obj = sub(resources, 'object', {'id': oid, 'type': 'model', '{'+prod+'}UUID': uid(name)})
        sub(sub(obj, 'components'), 'component', {'objectid': pid, '{'+prod+'}path': '/3D/Objects/object_1.model',
            '{'+prod+'}UUID': uid(name+'/part'), 'transform': transform()})
        cfg = ET.SubElement(config, 'object', {'id': oid})
        for k, v in {'name': name, 'extruder': '1', **PROCESS}.items():
            ET.SubElement(cfg, 'metadata', {'key': k, 'value': v})
        pcfg = ET.SubElement(cfg, 'part', {'id': pid, 'subtype': 'normal_part'})
        for k, v in [('name', name), ('extruder', '1'), ('source_file', str(HERE/'parts'/(name+'.stl')))]:
            ET.SubElement(pcfg, 'metadata', {'key': k, 'value': v})
        t = transform(*PLACEMENTS[name])
        sub(build, 'item', {'objectid': oid, 'transform': t, 'printable': '1', '{'+prod+'}UUID': uid(name+'/instance')})
        instance = ET.SubElement(plate, 'model_instance')
        for k, v in [('object_id', oid), ('instance_id', '0'), ('identify_id', str(i-1))]:
            ET.SubElement(instance, 'metadata', {'key': k, 'value': v})
        ET.SubElement(assembly, 'assemble_item', {'object_id': oid, 'instance_id': '0', 'transform': t, 'offset': '0 0 0'})
    members.update({'3D/3dmodel.model': xml(main), '3D/Objects/object_1.model': xml(partmodel),
                    'Metadata/project_settings.config': json.dumps(settings, indent=2),
                    'Metadata/model_settings.config': xml(config), 'Metadata/slice_info.config': '<config/>'})
    path = HERE/'diting-case-v1-K3.3mf'
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in members.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 6, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    return {'path': path.name, 'sha256': sha(path), 'template_sha256': sha(TEMPLATE),
            'unit': 'millimeter', 'settings': PROCESS, 'placements_xy_mm': PLACEMENTS,
            'minimum_xy_bbox_gap_mm': 6.35, 'native_project_not_cached_toolpaths': True}


def render_views(objects):
    scene = bpy.context.scene
    scene.render.engine = 'BLENDER_WORKBENCH'
    scene.render.resolution_x, scene.render.resolution_y = 1600, 1200
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = 'PNG'
    scene.render.film_transparent = False
    shade = scene.display.shading
    shade.light, shade.studio_light, shade.color_type = 'STUDIO', 'paint.sl', 'OBJECT'
    shade.show_shadows, shade.show_cavity = True, True
    shade.cavity_type, shade.curvature_ridge_factor, shade.curvature_valley_factor = 'BOTH', 1.3, 1.1
    shade.show_specular_highlight = True
    shade.background_type, shade.background_color = 'WORLD', (.06, .075, .1)
    scene.world.color = (.065, .075, .1)
    camera_data = bpy.data.cameras.new('Preview camera')
    camera = bpy.data.objects.new('Preview camera', camera_data)
    scene.collection.objects.link(camera); scene.camera = camera
    camera_data.type = 'ORTHO'; camera_data.lens = 45
    def render(name, direction, positions=None, print_layout=False, include=None):
        display = []
        for original in objects.values():
            original.hide_render = True
        for key, original in objects.items():
            if include and key not in include:
                continue
            ob = clone(original, 'display-'+key)
            ob.hide_render = False; ob.color = COLORS[key]
            if print_layout:
                for v, p in zip(ob.data.vertices, print_vertices(original, key)):
                    v.co = p
                dx, dy = PLACEMENTS[key]
                ob.location = (dx, dy, 0)
            else:
                ob.location = (positions or {}).get(key, (0, 0, 0))
            display.append(ob)
        bpy.context.view_layer.update()
        points = [ob.matrix_world @ v.co for ob in display for v in ob.data.vertices]
        center = Vector([(max(p[j] for p in points)+min(p[j] for p in points))/2 for j in range(3)])
        camera.location = center+Vector(direction).normalized()*150
        camera.rotation_euler = (center-camera.location).to_track_quat('-Z', 'Y').to_euler()
        inverse = camera.rotation_euler.to_matrix().transposed()
        projected = [inverse @ (p-center) for p in points]
        width = max(p.x for p in projected)-min(p.x for p in projected)
        height = max(p.y for p in projected)-min(p.y for p in projected)
        # A 3D AABB centre is not necessarily the projected silhouette centre.
        shift = Vector(((max(p.x for p in projected)+min(p.x for p in projected))/2,
                        (max(p.y for p in projected)+min(p.y for p in projected))/2, 0))
        camera.location += camera.rotation_euler.to_matrix() @ shift
        camera_data.ortho_scale = max(width, height*4/3)*1.18
        scene.render.filepath = str(HERE/'previews'/name)
        bpy.ops.render.render(write_still=True)
        for ob in display:
            bpy.data.objects.remove(ob, do_unlink=True)
    render('assembly-front.png', (40, -50, 80))
    render('assembly-back.png', (-40, 60, -80))
    render('exploded.png', (50, -45, 75), {'case-front': (0, 0, 12), 'case-back': (0, 0, -7), 'clip': (0, 0, -23)})
    render('exploded-back.png', (-45, 55, -80), {'case-front': (0, 0, 12), 'case-back': (0, 0, -7), 'clip': (0, 0, -23)})
    render('print-layout.png', (45, -65, 95), print_layout=True)
    render('front-orthographic.png', (0, 0, 100))
    render('front-inside.png', (0, -22, -100), include=['case-front'])
    render('back-slot.png', (-22, 28, -100), include=['case-back'])
    render('clip-side.png', (100, 0, 0), include=['clip'])
    for obj in objects.values():
        obj.hide_render = False
    return {'images_generated': ['assembly-front.png', 'assembly-back.png', 'exploded.png', 'exploded-back.png',
                                 'print-layout.png', 'front-orthographic.png', 'front-inside.png', 'back-slot.png', 'clip-side.png'],
            'human_visual_review': 'pending; recorded separately after opening generated PNGs'}


def main():
    for folder in ('parts', 'previews'):
        (HERE/folder).mkdir(exist_ok=True)
    bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)
    bpy.context.scene.unit_settings.system = 'METRIC'
    bpy.context.scene.unit_settings.scale_length = .001
    print('BUILD front', flush=True)
    front = chamfered_plate('case-front', BODY_X, BODY_Y, BODY_Z, OUT_R, REAR_CHAMFER, FRONT_CHAMFER)
    ear = [(EAR_R*math.cos(math.pi+i*math.pi/(SEGMENTS//2)), EAR_CY+EAR_R*math.sin(math.pi+i*math.pi/(SEGMENTS//2))) for i in range(SEGMENTS//2+1)]
    ear += [(EAR_W/2, EAR_TOP), (-EAR_W/2, EAR_TOP)]
    union(front, prism('ear', ear, *EAR_Z))
    subtract(front, prism('cavity', rr((-CAV_W/2, CAV_W/2), (0, CAV_L), CAV_R), -15.3, -FRONT))
    subtract(front, box('USB U opening', USB_X, (21.9, 24), (-15.3, USB_Z[1])))
    union(front, prism('conduit', circle(*MIC_XY, MIC_OD/2), MIC_TIP, -1.1))
    subtract(front, prism('microphone bore', circle(*MIC_XY, MIC_ID/2), -2.5, .1))
    for x in PIN_X:
        subtract(front, prism('pinhole', circle(x, PIN_Y, PIN_D/2), -1.3, .1))
    for x in LED_X:
        subtract(front, prism('LED pocket', circle(x, LED_Y, LED_D/2), -1.3, -LED_SKIN))
    for side in (-1, 1):
        profile = [(side*10.1, SNAP_Z-SNAP_GROOVE_H/2),
                   (side*(10.1+SNAP_GROOVE_DEPTH), SNAP_Z),
                   (side*10.1, SNAP_Z+SNAP_GROOVE_H/2)]
        subtract(front, prism('snap groove', profile, *GROOVE_Y, axis='Y'))
    subtract(front, box('pry notch', (-2, 2), (-1.4, .1), (-15.3, -14.4)))
    subtract(front, prism('lanyard hole', circle(0, EAR_CY, EAR_HOLE/2), -3.1, .1))

    print('BUILD back', flush=True)
    back = chamfered_plate('case-back', BACK_X, BACK_Y, BACK_Z, BACK_R, BACK_CHAMFER, BACK_CHAMFER)
    union(back, box('USB tongue', TONGUE_X, TONGUE_Y, TONGUE_Z))
    for side in (-1, 1):
        profile = [(side*10, SNAP_Z-SNAP_H/2), (side*(10+SNAP_PROJECTION), SNAP_Z),
                   (side*10, SNAP_Z+SNAP_H/2)]
        union(back, prism('snap ridge', profile, *SNAP_Y, axis='Y'))
    slot_profile = [(SLOT_LEFT_MOUTH, -15.3), (SLOT_RIGHT, -15.3),
                    (SLOT_RIGHT, SLOT_Z[1]), (SLOT_LEFT_FLOOR, SLOT_Z[1]),
                    (SLOT_LEFT_MOUTH, SLOT_Z[0])]
    subtract(back, prism('single dovetail slot', slot_profile, SLOT_Y[0], SLOT_Y[1]+.1, axis='Y'))

    print('BUILD clip', flush=True)
    clip = box('clip', CLIP_X, CLIP_BASE_Y, CLIP_BASE_Z)
    bend = [(BEND_CYZ[0]+BEND_RO*math.cos(math.pi/2+i*math.pi/(SEGMENTS//2)),
             BEND_CYZ[1]+BEND_RO*math.sin(math.pi/2+i*math.pi/(SEGMENTS//2))) for i in range(SEGMENTS//2+1)]
    bend += [(BEND_CYZ[0]+BEND_RI*math.cos(3*math.pi/2-i*math.pi/(SEGMENTS//2)),
              BEND_CYZ[1]+BEND_RI*math.sin(3*math.pi/2-i*math.pi/(SEGMENTS//2))) for i in range(SEGMENTS//2+1)]
    union(clip, prism('U bend', bend, *CLIP_X, axis='X'))
    union(clip, box('spring', CLIP_X, SPRING_Y, SPRING_Z))
    union(clip, prism('grip rib', [(BUMP_Y-BUMP_HALF_L, SPRING_Z[1]),
                     (BUMP_Y, SPRING_Z[1]+BUMP_H), (BUMP_Y+BUMP_HALF_L, SPRING_Z[1])], *CLIP_X, axis='X'))
    lip = [(SPRING_Y[1], SPRING_Z[0]), (LIP_END_Y, SPRING_Z[0]-LIP_FLARE),
           (LIP_END_Y, SPRING_Z[1]-LIP_FLARE), (SPRING_Y[1], SPRING_Z[1])]
    union(clip, prism('lead-in lip', lip, *CLIP_X, axis='X'))
    key = [(KEY_ROOT_LEFT, KEY_Z[0]), (KEY_RIGHT, KEY_Z[0]),
           (KEY_RIGHT, KEY_Z[1]), (KEY_TOP_LEFT, KEY_Z[1])]
    union(clip, prism('single dovetail key', key, *KEY_Y, axis='Y'))
    objects = {'case-front': front, 'case-back': back, 'clip': clip}

    report = {'stage': 'geometry_checked', 'unit': 'millimeter', 'blender_version': bpy.app.version_string,
              'design_sha256': sha(DESIGN), 'parameters_source': '../DESIGN.md (revised 2026-11-06)',
              'command': '/Applications/Blender.app/Contents/MacOS/Blender -b --factory-startup --python enclosure/v1/build.py',
              'extra_environment_variables': {},
              'parts': {}, 'assembly_intersections': {}, 'fits': {},
              'assumptions': ['外边倒角应用在盖板上下外边；舌片与倒角后盖板按公共面精确并集。',
                              '夹紧凸棱未规定沿 y 宽度，采用以 y=17 为中心、底宽1.2、高0.6的三角棱。',
                              '导入翘边在 y=20..22.5 线性外翘1.0，保持z方向1.4厚度。',
                              '主身外形25.0长不含挂绳耳；整件含耳32.3。单边槽最大宽6.8，原文7.6是更保守旧桥接描述。'],
              'machine_actions_performed_by_agent': False, 'physical_result_verified': False}
    parts = {}
    for name, obj in objects.items():
        triangulate_quantize(obj)
        stats = mesh_stats(obj)
        print('QA', name, json.dumps(stats), flush=True)
        report['parts'][name] = {'assembly': stats, 'expected_assembly_dimensions_mm': EXPECTED[name]}
        report['parts'][name]['expected_assembly_bounds_mm'] = EXPECTED_BOUNDS[name]
        assert stats['non_manifold_edges'] == 0 and stats['connected_components'] == 1, (name, stats)
        assert stats['volume_mm3'] > 0 and stats['degenerate_faces'] == 0, (name, stats)
        assert all(abs(a-b) < .01 for a, b in zip(stats['dimensions_mm'], EXPECTED[name])), (name, stats)
        assert all(abs(a-b) < .01 for actual, expected in zip(stats['bounds_mm'], EXPECTED_BOUNDS[name])
                   for a, b in zip(actual, expected)), (name, stats)
        vertices = print_vertices(obj, name)
        triangles = [tuple(p.vertices) for p in obj.data.polygons]
        path = HERE/'parts'/(name+'.stl')
        write_stl(path, vertices, triangles)
        reread_v, reread_t = read_stl(path)
        reread = obj_mesh('QA reimport', reread_v, reread_t)
        exported = mesh_stats(reread); bpy.data.objects.remove(reread, do_unlink=True)
        assert exported['non_manifold_edges'] == 0 and exported['connected_components'] == 1 and exported['volume_mm3'] > 0
        assert abs(exported['bounds_mm'][2][0]) < .00001
        assert abs(exported['volume_mm3']-stats['volume_mm3']) < .01
        report['parts'][name].update({'print_stl': exported, 'stl_sha256': sha(path),
                                      'export_quantization_mm': 10**-EXPORT_DIGITS,
                                      'print_transform': {'case-front': '(-x, y+8.5, -z)', 'case-back': '(x, y-0.1, z+15.2)',
                                                          'clip': '(z+19.8, y-1, 6-x)'}[name]})
        parts[name] = (vertices, triangles)
    for a, b in itertools.combinations(objects, 2):
        v = intersection(objects[a], objects[b])
        report['assembly_intersections'][a+' / '+b] = v
        assert v < .01, (a, b, v)
    for name, extents in [('board_main', (BOARD_X, BOARD_Y, BOARD_Z)),
                          ('usb_block', (USB_BLOCK_X, USB_BLOCK_Y, USB_BLOCK_Z))]:
        envelope = box(name, *extents)
        hits = {key: intersection(obj, envelope) for key, obj in objects.items()}
        clearance = distance(front, envelope)
        report['fits'][name] = {'bounds_mm': extents, 'intersection_volume_mm3': hits, 'front_clearance': clearance}
        assert all(v < .01 for v in hits.values()), (name, hits)
        bpy.data.objects.remove(envelope, do_unlink=True)
    report['fits']['tongue_to_front'] = distance(front, back)
    report['fits']['tongue_to_front'].update({'x_side_clearance_mm': [
        abs(ray_coordinate(front, (0, 23, -10), (sign, 0, 0), 0) -
            ray_coordinate(back, (0, 23, -10), (sign, 0, 0), 0)) for sign in (-1, 1)],
                                             'clearance_method': 'rays through final mesh at y=23,z=-10',
                                             'tongue_to_USB_block_z_mm': .3,
                                             'PCB_end_y_clearance_mm': .15,
                                             'note': 'tongue upper face z=-5.16; front opening ends z=-1.4; nominal USB underside is -5.06'})
    report['fits']['dovetail'] = {'intersection_volume_mm3': report['assembly_intersections']['case-back / clip'],
        'right_x_clearance_mm': SLOT_RIGHT-KEY_RIGHT, 'left_root_horizontal_clearance_mm': KEY_ROOT_LEFT-SLOT_LEFT_MOUTH,
        'left_tip_horizontal_clearance_mm': KEY_TOP_LEFT-(SLOT_LEFT_MOUTH+(SLOT_LEFT_FLOOR-SLOT_LEFT_MOUTH)*.9),
        'left_slope_min_normal_clearance_mm': .07/math.sqrt(1+.8**2), 'depth_clearance_mm': .1,
        'stop_y_clearance_mm': .1, 'note': 'hard coordinates govern; sloped-side horizontal gap tapers 0.15 to 0.07; contact at base plane is intended'}
    measured = {}
    for label, z in [('near_root', -15.1999), ('near_tip', -14.3001)]:
        measured[label] = {side: abs(ray_coordinate(back, (0, 10, z), (sign, 0, 0), 0)-
                                    ray_coordinate(clip, (0, 10, z), (sign, 0, 0), 0))
                           for side, sign in [('left_horizontal_mm', -1), ('right_mm', 1)]}
    measured['depth_mm'] = abs(ray_coordinate(back, (0, 10, -14), (0, 0, -1), 2)-
                               ray_coordinate(clip, (0, 10, -14), (0, 0, -1), 2))
    measured['stop_y_mm'] = abs(ray_coordinate(back, (0, 10, -14.5), (0, -1, 0), 1)-
                                ray_coordinate(clip, (0, 10, -14.5), (0, -1, 0), 1))
    report['fits']['dovetail']['measured_final_mesh'] = measured
    assert all(v > 0 for values in (measured['near_root'], measured['near_tip']) for v in values.values())
    report['fits']['conduit_to_board_z_mm'] = BOARD_Z[1]-MIC_TIP if BOARD_Z[1] > MIC_TIP else MIC_TIP-BOARD_Z[1]
    report['fits']['conduit_to_nominal_microphone_z_mm'] = 1.5
    def bore_diameter(x, y, z):
        return ray_coordinate(front, (x, y, z), (1, 0, 0), 0)-ray_coordinate(front, (x, y, z), (-1, 0, 0), 0)
    report['features_measured_final_mesh'] = {
        'microphone': {'center_xy_mm': MIC_XY, 'bore_diameter_mm': bore_diameter(*MIC_XY, -.6),
                       'conduit_tip_z_mm': ray_coordinate(front, (1, MIC_XY[1], -3), (0, 0, 1), 2)},
        'pinhole_diameters_mm': [bore_diameter(x, PIN_Y, -.6) for x in PIN_X],
        'LED_skin_thicknesses_mm': [-ray_coordinate(front, (x, LED_Y, -1), (0, 0, 1), 2) for x in LED_X],
        'lanyard_hole_diameter_mm': bore_diameter(0, EAR_CY, -1.5),
        'USB_ceiling_z_mm': ray_coordinate(front, (0, 23, -6), (0, 0, 1), 2),
        'tongue_tip_z_mm': ray_coordinate(back, (0, 23, -5), (0, 0, -1), 2)}
    assert abs(report['features_measured_final_mesh']['microphone']['bore_diameter_mm']-MIC_ID) < .01
    assert all(abs(v-LED_SKIN) < .01 for v in report['features_measured_final_mesh']['LED_skin_thicknesses_mm'])
    if TEMPLATE and TEMPLATE.exists():
        report['native_3mf'] = native_project(parts)
    report['previews'] = render_views(objects)
    bpy.context.scene.render.filepath = str(HERE/'previews'/'assembly-front.png')
    dump(HERE/'geometry-report.json', report)
    print('HARK_CASE_V1_BUILD_OK', flush=True)


if __name__ == '__main__':
    main()
