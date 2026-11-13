"""独立重建 Hark DT-1 外壳；尺寸单位均为 mm。

blender -b --factory-startup --python enclosure/build_dt1.py -- --variant all --check
只需 Blender 自带的 Python / numpy；默认输出到新建的临时目录。
"""
from pathlib import Path
from collections import Counter
import argparse
import json
import math
import struct
import sys
import tempfile
import traceback

import bpy
import bmesh
from mathutils import Vector
from mathutils.bvhtree import BVHTree

# 实测包装（含电池、线和胶带）：宽 20.5、含 USB 长 24.5、厚 11.0。
PACK_W, PACK_L_USB, PACK_T = 20.5, 24.5, 11.0
USB_OUT, USB_W, USB_H, USB_TO_SHIELD = 2.0, 8.94, 3.26, 1.4
PCB_X = 0.0  # 从正面看，板子相对包装中心的横向偏移。
FIT_W, FIT_L, FIT_T = .8, .8, .8  # 各方向总余量；长方向先扣掉 USB 伸出量。
PACK_BODY_L = PACK_L_USB-USB_OUT
CAV_W, CAV_L, CAV_H, CAV_R = PACK_W+FIT_W, PACK_BODY_L+FIT_L, PACK_T+FIT_T, .5
WALL_SIDE, WALL_USB, FRONT, LID_T = 1.2, 1.8, 1.2, 2.0  # 侧壁、USB 端、面盖、底板。
# 装配坐标：x 向右，y 朝 USB，正面 z=0，内部在 z<0；内腔始于 y=0。
HALF_W = CAV_W/2
BODY_Y = (-WALL_SIDE, CAV_L+WALL_USB)
BACK_FLOOR_Z = -FRONT-CAV_H
PCB_EDGE_Y = CAV_L-.25
PCB_FRONT_Z = BACK_FLOOR_Z+PACK_T-USB_H
USB_TOP_Z = BACK_FLOOR_Z+PACK_T
SHIELD_TOP_Z = USB_TOP_Z-USB_TO_SHIELD
SPLIT_Z = round((PCB_FRONT_Z+USB_TOP_Z)/2, 2)  # USB 中线处分盖。
BACK_Z = (BACK_FLOOR_Z-LID_T, SPLIT_Z)
USB_HALF = USB_W/2+1.0
USB_X, USB_Z = (PCB_X-USB_HALF, PCB_X+USB_HALF), (PCB_FRONT_Z-1.0, -1.35)
COMPONENT_Y = PCB_EDGE_Y-16.1
MIC_XY, MIC_SLOT_W, MIC_SLOT_L, MIC_BAR = (PCB_X, COMPONENT_Y), 1.4, 4.4, .8
MIC_SLOTS = [MIC_XY[0]+i*(MIC_SLOT_W+MIC_BAR) for i in (-1, 0, 1)]
PIN_X, PIN_Y, PIN_D = (PCB_X-4.7, PCB_X+4.5), COMPONENT_Y, 1.6
LED_X, LED_Y, LED_D, LED_SKIN = (PCB_X-6.3, PCB_X+6.3), PCB_EDGE_Y-.9, 2.6, .36
LED_HOLE_D = 1.2
# 平面小牌，拱顶穿绳孔；前后 1.2 mm、45° 倒角。
TAG_APEX_Y, ARCH_CY, CORNER_R, CHAMFER = -7.7, 0.0, 2.5, 1.2
STONE_A = HALF_W+WALL_SIDE
STONE_CY, STONE_B = (TAG_APEX_Y+BODY_Y[1])/2, (BODY_Y[1]-TAG_APEX_Y)/2
ZTOP = 0.0
CORD_XY, CORD_D = (0, -3.7), 2.6
# 插舌、倒钩、槽和防滑挡块；改尺寸后仍会检查板子包络与装配间隙。
STOP_INNER, STOP_DEPTH, STOP_CLEAR, STOP_LEN = 9.85, 1.97, .2, 1.5
TONGUE_CLEAR, TONGUE_T, TONGUE_LEN, TONGUE_DROP = .15, .8, 14, 1.8
TONGUE_OUTER = HALF_W-TONGUE_CLEAR
TONGUE_INNER = TONGUE_OUTER-TONGUE_T
MID_Y = CAV_L/2
TONGUE_Y, TONGUE_Z = (MID_Y-TONGUE_LEN/2, MID_Y+TONGUE_LEN/2), (SPLIT_Z-TONGUE_DROP, SPLIT_Z)
RIB_OUTER, RIB_Z = HALF_W, (SPLIT_Z, -FRONT)
SNAP_Y, GROOVE_Y, SNAP_Z = (MID_Y-4, MID_Y+4), (MID_Y-4.5, MID_Y+4.5), SPLIT_Z-.87
SNAP_PROJECTION, SNAP_BACK, SNAP_LEAD, SNAP_GROOVE_DEPTH, SNAP_GROOVE_GAP = .45, .3, .9, .55, .08
SLOT_RIGHT, SLOT_LEFT_MOUTH, SLOT_LEFT_FLOOR = 3, -3, -3.8  # 底面预留单边燕尾槽。
SLOT_Z, SLOT_Y = (BACK_Z[0], BACK_Z[0]+1), (2, BODY_Y[1])
PRY_WIDTH, PRY_HALF_HEIGHT = 6.0, .6  # 上下各开 0.6 mm，合成 1.2 mm 高撬口。
PRY_BACK_INSET, PRY_FRONT_INSET = .7, .5
ENVELOPES = {  # 实测包装拆成电池/PCB、正面元件和 USB 三块验算。
    'battery_and_pcb': ((-PACK_W/2, PACK_W/2), (PCB_EDGE_Y-PACK_BODY_L, PCB_EDGE_Y), (BACK_FLOOR_Z, PCB_FRONT_Z-1.0)),
    'front_components': ((PCB_X-9.0, PCB_X+9.0), (PCB_EDGE_Y-PACK_BODY_L, PCB_EDGE_Y), (PCB_FRONT_Z-1.0, SHIELD_TOP_Z)),
    'usb_block': ((PCB_X-USB_W/2, PCB_X+USB_W/2), (PCB_EDGE_Y, PCB_EDGE_Y+USB_OUT), (PCB_FRONT_Z, USB_TOP_Z)),
}
# 刻纹节点：(x, y, 出射切线 dx, dy, 全线宽)；左半由镜像生成，孔位保留实测偏移。
EAR_KNOTS = [(6.4,-3.55,.95,0,1.0), (8.6,-1.5,.55,1.1,1.1), (9.0,1.3,-.65,.5,1.15),
             (7.2,1.8,-1.4,.15,1.15), (3.5,2.8,-.7,-1.1,1.1), (3.3,.1,0,-1.0,1.0), (4.8,-2.2,.7,-.7,1.0)]
EAR_ROOT_KNOTS = [(5.45,.4,0,-.55,1.0), (6.75,-.6,.718,0,1.0)]
FACE_KNOTS = [(3.65,3.25,.8,.3,1.1), (6.6,4.65,1.2,.65,1.15), (7.9,7.3,-.1,1.2,1.15),
              (5.6,10.1,-1.0,.05,1.1), (3.9,10.6,-.5,.6,1.1), (3.6,13.1,.3,1.0,1.1), (2.25,15.15,-.8,.05,1.0)]
CLOUD_KNOTS = [(7.9,7.3,.7,1.4,1.05), (9.0,10.5,.1,1.2,1.05), (8.9,14.2,-.8,1.0,1.05),
               (6.6,15.3,-.55,-.4,1.05), (6.15,13.45,0,-.4142,1.0), (6.9,12.7,.4142,0,1.0)]
JAW_KNOTS = [(6.6,15.3,-.7,.6,1.05), (5.2,17.75,-.25,.7,1.1)]
LIP_HALF_KNOTS = [(6.6,19.9,-.2,-.8,1.05), (5.3,18.0,-.9,0,1.15),
                  (3.3,19.65,-.9,0,1.1), (0,18.9,-1.1,0,1.1)]
ENGRAVE_SHALLOW, ENGRAVE_DEEP = .20, .36  # 浅线、深线；深线下剩 1.2−0.36=0.84 mm。
EAR_ROOT_START_T, EAR_ROOT_START_HANDLE = 3/16, (-.4, -.5, 1.05)
# 安全下限与离散精度沿用成品；改大尺寸不应靠降低阈值通过。
WALL_MIN, ART_MIN, EDGE_MARGIN = .8, .8, .6
SEGMENTS, ROUND_QUARTER, EXPORT_DIGITS = 96, 24, 3
QUANTIZATION_ALLOWANCE = .003
CURVE_MAX_CHORD, CURVE_MAX_TURN = .05, math.radians(2)
# 布尔刀具超出外形；默认范围保持成品构造，放大外壳时自动外扩。
CUT_X = (-max(20, STONE_A+1), max(20, STONE_A+1))
CUT_Y = (min(-12, TAG_APEX_Y-1), max(32, BODY_Y[1]+1))
SIDE_TOOL_LIMIT = max(17, STONE_A+1)
HERE = Path(__file__).resolve().parent


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
        if name == 'case-front': p = (-x, y-STONE_CY+STONE_B+1, ZTOP-z)
        elif name == 'case-back': p = (x, y-STONE_CY+STONE_B+1, z-BACK_Z[0])
        else: raise ValueError(name)
        out.append(tuple(round(c, EXPORT_DIGITS) for c in p))
    return out


def write_stl(path, vertices, triangles):
    with path.open('wb') as f:
        f.write(b'Hark DT-1; millimeters; exact axis permutation'.ljust(80, b'\0'))
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


def tag_outline(d):
    """Closed tag outline, inset by d. Same point count for every d so rings can be lofted."""
    w, top, foot, r = STONE_A-d, ARCH_CY-TAG_APEX_Y-d, BODY_Y[1]-d, max(CORNER_R-d, .3)
    pts = [(w*math.cos(math.pi*i/120), ARCH_CY-top*math.sin(math.pi*i/120)) for i in range(121)]
    pts += [(-w, ARCH_CY+(foot-r-ARCH_CY)*i/6) for i in range(1, 7)]
    pts += [(-w+r-r*math.cos(math.pi/2*i/16), foot-r+r*math.sin(math.pi/2*i/16)) for i in range(1, 17)]
    pts += [(-w+r+(2*w-2*r)*i/8, foot) for i in range(1, 8)]
    pts += [(w-r+r*math.sin(math.pi/2*i/16), foot-r+r*math.cos(math.pi/2*i/16)) for i in range(0, 17)]
    pts += [(w, foot-r-(foot-r-ARCH_CY)*i/6) for i in range(1, 6)]
    return pts


def tag_loft(name):
    bottom = BACK_Z[0]
    return loft(name, [(bottom, tag_outline(CHAMFER)), (bottom+CHAMFER, tag_outline(0)),
                       (ZTOP-CHAMFER, tag_outline(0)), (ZTOP, tag_outline(CHAMFER))])


def ridge_profile(side):
    # Long ramp toward the tongue tip for entry, steep face toward the root to hold.
    return [(side*TONGUE_OUTER, SNAP_Z+SNAP_BACK), (side*(TONGUE_OUTER+SNAP_PROJECTION), SNAP_Z),
            (side*TONGUE_OUTER, SNAP_Z-SNAP_LEAD)]


def groove_profile(side):
    slope = SNAP_BACK/SNAP_PROJECTION
    contact = SNAP_Z+(TONGUE_OUTER+SNAP_PROJECTION-HALF_W)*slope+SNAP_GROOVE_GAP
    deep = contact-(SNAP_GROOVE_DEPTH*slope)
    return [(side*HALF_W, contact), (side*(HALF_W+SNAP_GROOVE_DEPTH), deep),
            (side*(HALF_W+SNAP_GROOVE_DEPTH), deep-.3), (side*HALF_W, SNAP_Z-.7)]


def cubic_value(c, t):
    u = 1-t
    point = tuple(u*u*u*c[0][i]+3*u*u*t*c[1][i]+3*u*t*t*c[2][i]+t*t*t*c[3][i] for i in (0, 1))
    tangent = tuple(3*u*u*(c[1][i]-c[0][i])+6*u*t*(c[2][i]-c[1][i])+3*t*t*(c[3][i]-c[2][i]) for i in (0, 1))
    second = tuple(6*u*(c[2][i]-2*c[1][i]+c[0][i])+6*t*(c[3][i]-2*c[2][i]+c[1][i]) for i in (0, 1))
    return point, tangent, second


def cubic_minimum_radius(control):
    """Evaluate both ends and every real stationary point of cubic curvature.

    With A=|p'|^2 and B=cross(p',p''), R^2=A^3/B^2.
    Its finite extrema solve 3 A' B - 2 A B' = 0; no sampled minimum can miss a cusp.
    """
    import numpy as np  # Blender bundled
    from numpy.polynomial import polynomial as poly
    c = np.asarray(control, dtype=float)
    derivative = np.array([3*(c[1]-c[0]), 6*(c[2]-2*c[1]+c[0]),
                           3*(c[3]-3*c[2]+3*c[1]-c[0])])
    dx, dy = derivative.T
    ddx, ddy = poly.polyder(dx), poly.polyder(dy)
    a = poly.polyadd(poly.polymul(dx, dx), poly.polymul(dy, dy))
    b = poly.polysub(poly.polymul(dx, ddy), poly.polymul(dy, ddx))
    def roots(p):
        return [float(v.real) for v in poly.polyroots(poly.polytrim(p, tol=1e-11))
                if abs(v.imag) < 1e-7 and 0 < v.real < 1]
    assert min(poly.polyval(t, a) for t in [0., 1.]+roots(poly.polyder(a))) > 1e-10, 'zero Bezier tangent'
    stationary = poly.polysub(3*poly.polymul(poly.polyder(a), b),
                             2*poly.polymul(a, poly.polyder(b)))
    radius, parameter = min((math.hypot(*cubic_value(control, t)[1])**3 /
                             max(abs(poly.polyval(t, b)), 1e-15), t)
                            for t in [0., 1.]+roots(stationary))
    return float(radius), float(parameter)


def smooth_stroke(name, knots, depth=ENGRAVE_DEEP, closed=False):
    # Each knot stores x,y, outgoing tangent handle dx,dy, and full groove width.
    # Incoming handle is its exact negative: all joins are C1, not rounded polylines.
    samples, widths, normals, radii, extrema = [], [], [], [], []
    def parameters(control, lo=0., hi=1., level=0):
        _, d0, _ = cubic_value(control, 0)
        _, d1, _ = cubic_value(control, 1)
        angle = abs(math.atan2(d0[0]*d1[1]-d0[1]*d1[0], d0[0]*d1[0]+d0[1]*d1[1]))
        flat = max(point_segment_distance(q, control[0], control[3]) for q in control[1:3])
        if (math.dist(control[0], control[3]) <= CURVE_MAX_CHORD and flat <= .001 and angle <= CURVE_MAX_TURN) or level >= 22:
            return [lo, hi]
        a, b, c = [tuple((p[i]+q[i])/2 for i in (0, 1)) for p, q in zip(control, control[1:])]
        d, e = [tuple((p[i]+q[i])/2 for i in (0, 1)) for p, q in ((a, b), (b, c))]
        f = tuple((d[i]+e[i])/2 for i in (0, 1)); mid=(lo+hi)/2
        return parameters([control[0], a, d, f], lo, mid, level+1)[:-1]+parameters([f, e, c, control[3]], mid, hi, level+1)
    pairs = list(zip(knots, knots[1:]+knots[:1])) if closed else list(zip(knots, knots[1:]))
    for k, (a, b) in enumerate(pairs):
        control = [(a[0], a[1]), (a[0]+a[2], a[1]+a[3]), (b[0]-b[2], b[1]-b[3]), (b[0], b[1])]
        radius, parameter = cubic_minimum_radius(control)
        extrema.append((radius, k, parameter))
        ts = parameters(control)
        if k: ts=ts[1:]
        for t in ts:
            point, tangent, second = cubic_value(control, t)
            length = math.hypot(*tangent); assert length > 1e-6, name
            cross = abs(tangent[0]*second[1]-tangent[1]*second[0])
            radius = length**3/cross if cross > 1e-9 else 1e9
            blend = t*t*(3-2*t)
            samples.append(point); normals.append((-tangent[1]/length, tangent[0]/length))
            widths.append(a[4]+(b[4]-a[4])*blend); radii.append(radius)
    if closed:
        samples.pop(); normals.pop(); widths.pop(); radii.pop()
    return {'name': name, 'points': samples, 'normals': normals, 'widths_mm': widths,
            'width_mm': min(widths), 'depth_mm': depth, 'closed': closed, 'knots': knots,
            'minimum_centerline_radius_mm': min(extrema)[0],
            'minimum_radius_witness_segment_t': list(min(extrema)[1:]),
            'curvature_method': 'All real stationary roots of 3 A\' B - 2 A B\', plus both one-sided knot endpoints',
            'maximum_sample_chord_mm': max(math.dist(a, b) for a, b in zip(samples, samples[1:]+samples[:1] if closed else samples[1:])),
            'construction': 'Adaptive C1 cubic Bezier, analytic tangent normals and smooth width interpolation'}


def face_strokes():
    strokes = []
    ear = EAR_KNOTS
    a,b=ear[3:5]
    control=[a[:2],(a[0]+a[2],a[1]+a[3]),(b[0]-b[2],b[1]-b[3]),b[:2]]
    root_start=cubic_value(control,EAR_ROOT_START_T)[0]  # 耳下弧上的精确接点。
    root = [(*root_start,*EAR_ROOT_START_HANDLE)]+EAR_ROOT_KNOTS
    face = FACE_KNOTS
    cloud = CLOUD_KNOTS
    bridge = JAW_KNOTS
    for side in (-1, 1):
        for kind, knots, depth, closed in [('ear',ear,ENGRAVE_DEEP,True),('ear-root',root,ENGRAVE_SHALLOW,False),
                ('face-eye-nose',face,ENGRAVE_DEEP,False),('cloud-cheek',cloud,ENGRAVE_SHALLOW,False),('jaw-flow',bridge,ENGRAVE_SHALLOW,False)]:
            transformed = [(side*x,y,side*tx,ty,w) for x,y,tx,ty,w in knots]
            strokes.append(smooth_stroke(f'{kind}-{side}', transformed, depth, closed))
    lip = LIP_HALF_KNOTS.copy()
    lip += [(-x,y,tx,-ty,w) for x,y,tx,ty,w in reversed(lip[:-1])]
    strokes.append(smooth_stroke('wide-muzzle-and-fang-ends', lip, ENGRAVE_DEEP))
    return strokes


def clean_polygon(points):
    clean = []
    for p in points:
        q=tuple(round(c, EXPORT_DIGITS) for c in p)
        if not clean or q != clean[-1]: clean.append(q)
    if clean[-1] == clean[0]: clean.pop()
    changed=True
    while changed:
        changed=False
        for i in range(len(clean)):
            a,b,c=clean[i-1],clean[i],clean[(i+1)%len(clean)]
            if abs((b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0])) < 1e-10:
                clean.pop(i); changed=True; break
    return clean


def ribbon_boundaries(stroke):
    left,right=[],[]
    for p,n,w in zip(stroke['points'],stroke['normals'],stroke['widths_mm']):
        left.append((p[0]+n[0]*w/2,p[1]+n[1]*w/2))
        right.append((p[0]-n[0]*w/2,p[1]-n[1]*w/2))
    return left,right


def ribbon_prism(stroke):
    left,right=ribbon_boundaries(stroke)
    low, high=-stroke['depth_mm'],.1
    if stroke['closed']:
        # Annular extrusion of a 2D closed ribbon. Equal sample counts make clean quad caps.
        n=len(left)
        vs=[(round(x,3),round(y,3),z) for z in (low,high) for loop in (left,right) for x,y in loop]
        fs=[]
        for i in range(n):
            j=(i+1)%n
            fs += [(i,j,n+j,n+i),(2*n+i,3*n+i,3*n+j,2*n+j),
                   (i,2*n+i,2*n+j,j),(n+i,n+j,3*n+j,3*n+i)]
        return obj_mesh(stroke['name'],vs,fs)
    polygon=left[:]
    for endpoint,normal,radius in [(stroke['points'][-1],stroke['normals'][-1],stroke['widths_mm'][-1]/2)]:
        angle=math.atan2(normal[1],normal[0])
        polygon += [(endpoint[0]+radius*math.cos(angle-math.pi*i/64),endpoint[1]+radius*math.sin(angle-math.pi*i/64)) for i in range(1,65)]
    polygon += list(reversed(right[:-1]))
    endpoint,normal,radius=stroke['points'][0],stroke['normals'][0],stroke['widths_mm'][0]/2
    angle=math.atan2(-normal[1],-normal[0])
    polygon += [(endpoint[0]+radius*math.cos(angle-math.pi*i/64),endpoint[1]+radius*math.sin(angle-math.pi*i/64)) for i in range(1,65)]
    return prism(stroke['name'],clean_polygon(polygon),low,high)


def point_segment_distance(p, a, b):
    dx, dy = b[0]-a[0], b[1]-a[1]
    t = max(0., min(1., ((p[0]-a[0])*dx+(p[1]-a[1])*dy)/(dx*dx+dy*dy)))
    return math.hypot(p[0]-a[0]-t*dx, p[1]-a[1]-t*dy)


def edges(poly):
    return list(zip(poly, poly[1:]+poly[:1]))


def inside(p, poly):
    result = False
    for a, b in edges(poly):
        if (a[1] > p[1]) != (b[1] > p[1]):
            x = a[0]+(p[1]-a[1])*(b[0]-a[0])/(b[1]-a[1])
            if p[0] < x:
                result = not result
    return result


def opening_polygons():
    holes = {f'pin-{i}': circle(x, PIN_Y, PIN_D/2) for i, x in enumerate(PIN_X)}
    holes.update({f'led-{i}': circle(x, LED_Y, LED_HOLE_D/2) for i, x in enumerate(LED_X)})
    holes['cord'] = circle(*CORD_XY, CORD_D/2)
    for i, x in enumerate(MIC_SLOTS):
        holes[f'mic-{i}'] = [(x-MIC_SLOT_W/2, MIC_XY[1]-MIC_SLOT_L/2),
                             (x+MIC_SLOT_W/2, MIC_XY[1]-MIC_SLOT_L/2),
                             (x+MIC_SLOT_W/2, MIC_XY[1]+MIC_SLOT_L/2),
                             (x-MIC_SLOT_W/2, MIC_XY[1]+MIC_SLOT_L/2)]
    return holes


def path_samples(stroke, pitch=.1):
    points, widths=stroke['points'],stroke['widths_mm']
    output=[(points[0],widths[0]/2,0.)]; arc=0.; last=0.
    for i in range(1,len(points)):
        arc += math.dist(points[i-1],points[i])
        if arc-last >= pitch or i==len(points)-1:
            output.append((points[i],widths[i]/2,arc)); last=arc
    return output,arc+(math.dist(points[-1],points[0]) if stroke['closed'] else 0.)


def decoration_checks(strokes):
    import numpy as np  # bundled with Blender, no third-party installation
    flat=tag_outline(CHAMFER); holes=opening_polygons()
    margins=[]; hole_gaps=[]; widths=[]; web_checks=[]
    # Compute the real intersection instead of carrying an obsolete drawing node.
    ear=next(s for s in strokes if s['name']=='ear-1')
    root=next(s for s in strokes if s['name']=='ear-root-1')
    crossings=[]
    for a,b in edges(ear['points']):
        for c,d in zip(root['points'],root['points'][1:]):
            ux,uy=b[0]-a[0],b[1]-a[1]; vx,vy=d[0]-c[0],d[1]-c[1]
            determinant=ux*vy-uy*vx
            if abs(determinant)<1e-12: continue
            zx,zy=c[0]-a[0],c[1]-a[1]
            t=(zx*vy-zy*vx)/determinant; u=(zx*uy-zy*ux)/determinant
            if 0<=t<=1 and 0<=u<=1: crossings.append((a[0]+t*ux,a[1]+t*uy))
    crossings=sorted(set(tuple(round(v,9) for v in p) for p in crossings))
    assert len(crossings)==1, ('ear/root must have exactly one junction',crossings)
    # Junction centres follow the actual curve intersections; radius stays 3 mm.
    join_specs=[('ear','ear-root',crossings[0]),('ear','face-eye-nose',(3.65,3.25)),
                ('face-eye-nose','cloud-cheek',(7.9,7.3)),
                ('cloud-cheek','jaw-flow',(6.6,15.3)),('jaw-flow','wide-muzzle-and-fang-ends',(5.2,17.75))]
    junction_checks=[]
    for kind1,kind2,node in join_specs:
        for side in (-1,1):
            node=(side*abs(node[0]),node[1]); names=[f'{kind1}-{side}', f'{kind2}-{side}' if kind2!='wide-muzzle-and-fang-ends' else kind2]
            distances={}
            for name in names:
                st=next(s for s in strokes if s['name']==name)
                segments=edges(st['points']) if st['closed'] else zip(st['points'],st['points'][1:])
                distances[name]=min(point_segment_distance(node,a,b) for a,b in segments)
                assert distances[name] < max(st['widths_mm'])/2, ('junction not on both grooves',node,name)
            junction_checks.append({'node_xy_mm':node,'grooves':names,'centerline_distances_mm':distances,'excluded_radius_mm':3.0})
    sampled=[]
    for st in strokes:
        assert st['depth_mm'] in (ENGRAVE_SHALLOW,ENGRAVE_DEEP) and FRONT-st['depth_mm'] >= ART_MIN
        assert st['maximum_sample_chord_mm'] <= CURVE_MAX_CHORD+.000001
        assert st['minimum_centerline_radius_mm'] > max(st['widths_mm'])/2+.01, ('offset cusp',st['name'],st['minimum_centerline_radius_mm'])
        slopes=[abs((b-a)/2)/math.dist(p,q) for a,b,p,q in zip(st['widths_mm'],st['widths_mm'][1:],st['points'],st['points'][1:])]
        bound=min(st['widths_mm'])/math.sqrt(1+max(slopes,default=0)**2)*math.cos(CURVE_MAX_TURN/2)-QUANTIZATION_ALLOWANCE
        widths.append(bound); assert bound >= ART_MIN,st['name']
        samples,length=path_samples(st)
        sampled.append((st,samples,length))
        for point,radius,arc in samples:
            assert inside(point,flat),(st['name'],point)
            margin=min(point_segment_distance(point,a,b) for a,b in edges(flat))-radius-.053
            margins.append((margin,st['name'],point)); assert margin >= EDGE_MARGIN,('chamfer',margins[-1])
            for name,poly in holes.items():
                gap=min(point_segment_distance(point,a,b) for a,b in edges(poly))-radius-.053
                hole_gaps.append((gap,st['name'],name,point))
                assert not inside(point,poly) and gap >= ART_MIN,('hole web',hole_gaps[-1])
    for i,(st,ss,total) in enumerate(sampled):
        pa=np.array([q[0] for q in ss]); ra=np.array([q[1] for q in ss]); aa=np.array([q[2] for q in ss])
        for j in range(i,len(sampled)):
            ot,tt,olen=sampled[j]
            pb=np.array([q[0] for q in tt]); rb=np.array([q[1] for q in tt]); ab=np.array([q[2] for q in tt])
            delta=pa[:,None,:]-pb[None,:,:]
            gap=np.sqrt(np.sum(delta*delta,axis=2))-ra[:,None]-rb[None,:]-.203
            valid=np.ones(gap.shape,dtype=bool)
            if i==j:
                arc=np.abs(aa[:,None]-ab[None,:])
                if st['closed']: arc=np.minimum(arc,total-arc)
                valid &= arc > 3.5  # connected smooth bends / terminal tapered regions are not webs
            else:
                for kind1,kind2,node in join_specs:
                    for side in (-1,1):
                        n1=f'{kind1}-{side}'; n2=f'{kind2}-{side}'
                        if kind2=='wide-muzzle-and-fang-ends': n2=kind2
                        if {st['name'],ot['name']}=={n1,n2}:
                            loc=np.array([side*node[0],node[1]])
                            near1=np.sqrt(np.sum((pa-loc)**2,axis=1)) < 3.0
                            near2=np.sqrt(np.sum((pb-loc)**2,axis=1)) < 3.0
                            valid &= ~(near1[:,None]&near2[None,:])
            if np.any(valid):
                use=np.where(valid,gap,np.inf); index=np.unravel_index(np.argmin(use),use.shape)
                value=float(use[index]); witness=[value,st['name'],ot['name'],list(pa[index[0]]),list(pb[index[1]])]
                web_checks.append(witness)
    bad_webs = sorted((v for v in web_checks if v[0] < ART_MIN), key=lambda v:v[0])
    if bad_webs: print('NONLOCAL_WEB_FAILURES', json.dumps(bad_webs), flush=True)
    assert not bad_webs, ('nonlocal solid web', bad_webs)
    cord_flat=min(point_segment_distance(CORD_XY,a,b) for a,b in edges(flat))-CORD_D/2-.003
    cord_outer=min(point_segment_distance(CORD_XY,a,b) for a,b in edges(tag_outline(0)))-CORD_D/2-.003
    # 原绳孔只记录留筋；新刻纹到孔的留筋仍必须满足 ART_MIN。
    return {'stroke_count':len(strokes),'depths_mm':[ENGRAVE_SHALLOW,ENGRAVE_DEEP], 'curve_type':'C1 cubic Bezier with variable-width normal offset',
            'connected_junction_checks':junction_checks,
            'minimum_line_width_lower_bound_mm':min(widths),
            'minimum_solid_web_lower_bound_mm':min([v[0] for v in web_checks]+[v[0] for v in hole_gaps]),
            'solid_web_witness':min(web_checks+hole_gaps,key=lambda q:q[0]),
            'minimum_chamfer_clearance_lower_bound_mm':min(v[0] for v in margins),
            'minimum_remaining_face_wall_mm':FRONT-ENGRAVE_DEEP,'maximum_centerline_chord_mm':max(st['maximum_sample_chord_mm'] for st in strokes),
            'maximum_cubic_tangent_subdivision_angle_degrees':math.degrees(CURVE_MAX_TURN),
            'cord':{'center_xy_mm':CORD_XY,'diameter_mm':CORD_D,'flat_face_ligament_lower_bound_mm':cord_flat,'outer_outline_ligament_lower_bound_mm':cord_outer,
                    'measurement_only':True},
            'method':'Variable-width C1 Bezier normal offsets, chord <=0.05 mm, tangent subdivision <=2 degrees. '
                     'Normal width bound accounts for width slope, angular faceting, and 0.003 mm quantization. '
                     'Hole/edge checks sample <=0.15 mm and subtract 0.053 mm; nonlocal stroke-web checks subtract 0.203 mm. '
                     'Same-path arc neighbourhood <=3.5 mm and explicit connected-junction disks of radius 3 mm excluded. '
                     'Approximate feature/web audit, not an exhaustive medial-axis proof; exclusions are continuous turns or intentional joins, not standalone thin webs.',
            'strokes':strokes}


def engrave(obj,strokes):
    for st in strokes: subtract(obj,ribbon_prism(st))
    return obj


def flat_area_and_patches(obj):
    bm=bmesh.new(); bm.from_mesh(obj.data)
    pending={f for f in bm.faces if all(abs(v.co.z-ZTOP)<.00001 for v in f.verts)}
    areas=[]
    while pending:
        stack=[pending.pop()]; area=0.
        while stack:
            face=stack.pop(); area+=face.calc_area()
            for edge in face.edges:
                for adjacent in edge.link_faces:
                    if adjacent in pending: pending.remove(adjacent); stack.append(adjacent)
        areas.append(area)
    bm.free(); areas.sort(reverse=True)
    return {'bed_contact_area_mm2':sum(areas),'connected_flat_contact_regions':len(areas),'region_areas_mm2':areas,
            'method':'Actual Z=0 triangle areas; shared-edge flood fill. Closed ears intentionally create two large ear islands; area of each island checked.'}


def build_parts():
    stone = tag_loft('stone')
    front = boolean(clone(stone, 'case-front'), box('front half', CUT_X, CUT_Y, (SPLIT_Z, ZTOP+1)), 'INTERSECT')
    back = boolean(clone(stone, 'case-back'), box('back half', CUT_X, CUT_Y, (BACK_Z[0]-1, SPLIT_Z)), 'INTERSECT')
    front_cavity = prism('front cavity', rr((-CAV_W/2, CAV_W/2), (0, CAV_L), CAV_R), SPLIT_Z-.1, -FRONT)
    back_cavity = prism('back cavity', rr((-CAV_W/2, CAV_W/2), (0, CAV_L), CAV_R), BACK_FLOOR_Z, SPLIT_Z+.1)
    walls = {'front': distance(stone, front_cavity), 'back': distance(stone, back_cavity)}
    print('WALLS', {k: round(v['surface_distance_mm'], 3) for k, v in walls.items()}, flush=True)
    assert all(v['surface_distance_mm'] >= WALL_MIN for v in walls.values()), walls
    bpy.data.objects.remove(stone, do_unlink=True)
    subtract(front, front_cavity)
    subtract(back, back_cavity)
    subtract(front, box('upper USB notch', USB_X, (CAV_L-.1, CUT_Y[1]), (SPLIT_Z-.1, USB_Z[1])))
    subtract(back, box('lower USB notch', USB_X, (CAV_L-.1, CUT_Y[1]), (USB_Z[0], SPLIT_Z+.1)))
    for x in MIC_SLOTS:
        subtract(front, box('microphone slot', (x-MIC_SLOT_W/2, x+MIC_SLOT_W/2),
                            (MIC_XY[1]-MIC_SLOT_L/2, MIC_XY[1]+MIC_SLOT_L/2), (-FRONT-.1, ZTOP+.1)))
    for x in PIN_X:
        subtract(front, prism('pinhole', circle(x, PIN_Y, PIN_D/2), -FRONT-.1, ZTOP+.1))
    for x in LED_X:
        subtract(front, prism('LED pocket', circle(x, LED_Y, LED_D/2), -FRONT-.1, -LED_SKIN))
        subtract(front, prism('LED hole', circle(x, LED_Y, LED_HOLE_D/2), -LED_SKIN-.1, ZTOP+.1))
    for side in (-1, 1):
        union(front, box('root rib', tuple(sorted((side*TONGUE_INNER, side*RIB_OUTER))), TONGUE_Y, RIB_Z))
        union(front, box('insertion tongue', tuple(sorted((side*TONGUE_INNER, side*TONGUE_OUTER))), TONGUE_Y, TONGUE_Z))
        union(front, prism('snap ridge', ridge_profile(side), *SNAP_Y, axis='Y'))
    for ob in (front, back):
        subtract(ob, prism('cord hole', circle(*CORD_XY, CORD_D/2), BACK_Z[0]-.1, ZTOP+.1))

    for side in (-1, 1):
        subtract(back, prism('snap groove', groove_profile(side), *GROOVE_Y, axis='Y'))
        subtract(back, box('side pry notch', tuple(sorted((side*(HALF_W+PRY_BACK_INSET), side*SIDE_TOOL_LIMIT))),
                           (MID_Y-PRY_WIDTH/2, MID_Y+PRY_WIDTH/2), (SPLIT_Z-PRY_HALF_HEIGHT, SPLIT_Z+.1)))
        subtract(front, box('lid pry notch', tuple(sorted((side*(HALF_W+PRY_FRONT_INSET), side*SIDE_TOOL_LIMIT))),
                            (MID_Y-PRY_WIDTH/2, MID_Y+PRY_WIDTH/2), (SPLIT_Z-.1, SPLIT_Z+PRY_HALF_HEIGHT)))
    # Stops at both ends of each tongue: the snap groove is longer than its ridge, so nothing else locates y.
    for side in (-1, 1):
        profile = [(side*(HALF_W+.05), SPLIT_Z), (side*STOP_INNER, SPLIT_Z), (side*STOP_INNER, SPLIT_Z-STOP_DEPTH),
                   (side*(HALF_W+.05), SPLIT_Z-STOP_DEPTH-(HALF_W+.05-STOP_INNER))]
        for sign in (-1, 1):
            near = MID_Y+sign*(TONGUE_LEN/2+STOP_CLEAR)
            far = near+sign*STOP_LEN
            union(back, prism('tongue end stop', profile, min(near, far), max(near, far), axis='Y'))
    slot = [(SLOT_LEFT_MOUTH, BACK_Z[0]-.1), (SLOT_RIGHT, BACK_Z[0]-.1),
            (SLOT_RIGHT, SLOT_Z[1]), (SLOT_LEFT_FLOOR, SLOT_Z[1]), (SLOT_LEFT_MOUTH, SLOT_Z[0])]
    subtract(back, prism('future single dovetail slot', slot, SLOT_Y[0], SLOT_Y[1]+.1, axis='Y'))
    objects = {'case-front': front, 'case-back': back}

    return objects, walls


def require_mesh(obj):
    stats = mesh_stats(obj)
    assert stats['non_manifold_edges'] == 0, (obj.name, 'non-manifold', stats)
    assert stats['connected_components'] == 1, (obj.name, 'disconnected', stats)
    assert stats['volume_mm3'] > 0 and stats['degenerate_faces'] == 0, (obj.name, stats)
    return stats


def assembly_checks(objects):
    """在最终量化网格上检查装配、元件包络、根筋覆盖和卡扣间隙。"""
    front, back = objects['case-front'], objects['case-back']
    overlap = intersection(front, back)
    assert overlap < .01, ('assembly interference', overlap)
    result = {'front_back_overlap_mm3': overlap, 'envelopes': {}, 'root_ribs': {}, 'fits': {}}
    envelopes = {name: box(name, *extents) for name, extents in ENVELOPES.items()}
    for name, envelope in envelopes.items():
        hits = {key: intersection(obj, envelope) for key, obj in objects.items()}
        assert all(v < .01 for v in hits.values()), (name, hits)
        result['envelopes'][name] = hits
    for side in (-1, 1):
        rib = box('QA rib region', tuple(sorted((side*TONGUE_INNER, side*RIB_OUTER))), TONGUE_Y, RIB_Z)
        coverage = intersection(front, rib)
        expected = (RIB_OUTER-TONGUE_INNER)*(TONGUE_Y[1]-TONGUE_Y[0])*(RIB_Z[1]-RIB_Z[0])
        hits = {name: intersection(rib, envelope) for name, envelope in envelopes.items()}
        assert abs(coverage-expected) < .01, ('rib coverage', side, coverage, expected)
        assert all(v < .01 for v in hits.values()), ('rib interference', side, hits)
        result['root_ribs'][str(side)] = {'expected_mm3': expected, 'covered_mm3': coverage}
        bpy.data.objects.remove(rib, do_unlink=True)
    for envelope in envelopes.values():
        bpy.data.objects.remove(envelope, do_unlink=True)

    for side in (-1, 1):
        tongue_outer = abs(ray_coordinate(front, (side*16, MID_Y, TONGUE_Z[0]+.01), (-side, 0, 0), 0))
        cavity_side = abs(ray_coordinate(back, (0, MID_Y, TONGUE_Z[0]+.01), (side, 0, 0), 0))
        ridge_outer = abs(ray_coordinate(front, (side*16, MID_Y, SNAP_Z), (-side, 0, 0), 0))
        groove_depth = abs(ray_coordinate(back, (0, MID_Y, SNAP_Z), (side, 0, 0), 0))
        snap = prism('QA snap region', ridge_profile(side), *SNAP_Y, axis='Y')
        snap_clearance = distance(snap, back)['surface_distance_mm']
        bpy.data.objects.remove(snap, do_unlink=True)
        root_inner = abs(ray_coordinate(front, (0, MID_Y, -2.9), (side, 0, 0), 0))
        fits = {
            'tongue_to_cavity_side_mm': cavity_side-tongue_outer,
            'snap_apex_to_groove_depth_x_mm': groove_depth-ridge_outer,
            'snap_to_back_min_surface_mm': snap_clearance,
            'snap_y_end_clearance_mm': [
                abs(ray_coordinate(back, (side*(TONGUE_OUTER-.2), MID_Y, SNAP_Z), (0, sign, 0), 1)-
                    ray_coordinate(front, (side*(TONGUE_OUTER-.2), MID_Y, SNAP_Z), (0, sign, 0), 1)) for sign in (-1, 1)],
            'rib_to_components_x_mm': root_inner-(9.0+abs(PCB_X)),
            'tongue_tip_to_pcb_z_mm': TONGUE_Z[0]-ENVELOPES['battery_and_pcb'][2][1],
        }
        assert cavity_side-tongue_outer > 0 and groove_depth-ridge_outer > 0 and root_inner-(9+abs(PCB_X)) > 0, fits
        result['fits'][str(side)] = fits
    return result


def feature_checks(front):
    """功能孔在三个深度实测，避免阴刻或布尔运算误伤孔壁。"""
    def bore_diameter(x, y, z):
        return ray_coordinate(front, (x, y, z), (1, 0, 0), 0)-ray_coordinate(front, (x, y, z), (-1, 0, 0), 0)
    slots = [bore_diameter(x, MIC_XY[1], -.6) for x in MIC_SLOTS]
    leds = [bore_diameter(x, LED_Y, -.1) for x in LED_X]
    pins = {str(z): [bore_diameter(x, PIN_Y, z) for x in PIN_X] for z in (-.1, -.6, -1.15)}
    assert all(abs(w-MIC_SLOT_W) < .0011 for w in slots), slots
    assert all(abs(w-LED_HOLE_D) < .0011 for w in leds), leds
    assert all(abs(w-PIN_D) < .0011 for values in pins.values() for w in values), pins
    return {'mic_slot_widths_mm': slots, 'led_hole_diameters_mm': leds, 'pin_diameters_mm': pins}


def engraving_mesh_checks(front, strokes, uncarved_contact):
    """保留首层接触面积与最终网格刻深检查；不生成渲染。"""
    contact = flat_area_and_patches(front)
    contact['retained_fraction'] = contact['bed_contact_area_mm2']/uncarved_contact['bed_contact_area_mm2']
    assert contact['connected_flat_contact_regions'] <= 5, contact
    assert min(contact['region_areas_mm2']) >= 4.0, contact
    assert contact['retained_fraction'] >= .60, contact
    tree = BVHTree.FromPolygons([v.co for v in front.data.vertices], [tuple(p.vertices) for p in front.data.polygons])
    depths, arc_steps = [], []
    for st in strokes:
        samples, total = path_samples(st, .30)
        steps = [b[2]-a[2] for a, b in zip(samples, samples[1:])]
        if st['closed']:
            steps.append(total-samples[-1][2])
        assert max(steps) <= .350001, ('depth probe arc spacing', st['name'], max(steps))
        arc_steps.extend(steps)
        for (x, y), radius, arc in samples:
            hit = tree.ray_cast(Vector((x, y, 1)), Vector((0, 0, -1)))[0]
            assert hit is not None, (st['name'], x, y)
            depth = -hit.z
            assert min(abs(depth-ENGRAVE_SHALLOW), abs(depth-ENGRAVE_DEEP)) < .0011 and depth >= st['depth_mm']-.0011, (st['name'], depth)
            depths.append(depth)
    return {'first_layer_contact': contact, 'depth_probe_count': len(depths),
            'depth_range_mm': [min(depths), max(depths)], 'depth_probe_max_arc_step_mm': max(arc_steps)}


def triangle_signature(vertices, triangles):
    """三角面多重集合：忽略顶点/面的存储顺序，保留重复面计数。"""
    scale = 10**EXPORT_DIGITS
    points = [tuple(round(c*scale) for c in p) for p in vertices]
    return Counter(tuple(sorted(points[i] for i in t)) for t in triangles)


def stl_measurements(vertices, triangles):
    # 用双精度求和，避免 STL 面顺序改变导致体积累加误差。
    def signed_six_volume(t):
        a, b, c = (vertices[i] for i in t)
        return (a[0]*(b[1]*c[2]-b[2]*c[1]) + a[1]*(b[2]*c[0]-b[0]*c[2]) + a[2]*(b[0]*c[1]-b[1]*c[0]))
    bounds = [[min(p[i] for p in vertices), max(p[i] for p in vertices)] for i in range(3)]
    return {'triangles': len(triangles), 'bounds_mm': bounds,
            'volume_mm3': math.fsum(signed_six_volume(t) for t in triangles)/6}


def compare_stl(generated, reference, label):
    """严格比较，不以相近的包围盒/体积替代几何一致。"""
    actual_v, actual_t = read_stl(generated)
    expected_v, expected_t = read_stl(reference)
    actual = stl_measurements(actual_v, actual_t)
    expected = stl_measurements(expected_v, expected_t)
    actual_set, expected_set = triangle_signature(actual_v, actual_t), triangle_signature(expected_v, expected_t)
    missing, extra = expected_set-actual_set, actual_set-expected_set
    bounds_delta = max(abs(a-b) for pair_a, pair_b in zip(actual['bounds_mm'], expected['bounds_mm']) for a, b in zip(pair_a, pair_b))
    volume_delta = abs(actual['volume_mm3']-expected['volume_mm3'])
    ok = actual_set == expected_set
    result = {'ok': ok, 'quantization_mm': 10**-EXPORT_DIGITS, 'generated': actual, 'reference': expected,
              'missing_triangles': sum(missing.values()), 'extra_triangles': sum(extra.values()),
              'max_bounds_delta_mm': bounds_delta, 'volume_delta_mm3': volume_delta}
    print(f"CHECK {label}: {'OK' if ok else 'DIFFERENT'} "
          f"triangles={actual['triangles']}/{expected['triangles']} "
          f"volume_mm3={actual['volume_mm3']:.9f}/{expected['volume_mm3']:.9f} "
          f"max_bounds_delta_mm={bounds_delta:.9g} volume_delta_mm3={volume_delta:.9g} "
          f"quantized_triangle_multiset={'identical' if ok else 'different'} "
          f"grid_mm={10**-EXPORT_DIGITS:g} missing={sum(missing.values())} extra={sum(extra.values())}", flush=True)
    if not ok:
        result['missing_examples_grid_units'] = list(missing)[:3]
        result['extra_examples_grid_units'] = list(extra)[:3]
        print('DIFFERENCE', json.dumps(result, ensure_ascii=False), flush=True)
    return result


def output_paths(out, variant, blend):
    folder = out/f'dt1-{variant}'
    paths = [folder/'parts'/f'{name}.stl' for name in ('case-front', 'case-back')]
    paths.append(folder/'checks.json')
    if blend:
        paths.append(folder/f'hark-dt1-{variant}.blend')
    protected = [(HERE/f'dt1-{v}').resolve() for v in ('plain', 'carved')]
    # 即使用户把 --out 指向 enclosure 或通过符号链接绕回，也不覆盖仓库成品。
    for path in paths:
        if any(path.resolve().is_relative_to(base) for base in protected):
            raise ValueError('输出会覆盖仓库成品，请把 --out 指向新的目录。')
    return folder


def build_variant(variant, folder, check, blend):
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)
    bpy.context.scene.unit_settings.system = 'METRIC'
    bpy.context.scene.unit_settings.scale_length = .001
    print(f'BUILD {variant}', flush=True)
    strokes = face_strokes() if variant == 'carved' else []
    art = decoration_checks(strokes) if strokes else None
    objects, walls = build_parts()
    uncarved_contact = flat_area_and_patches(objects['case-front']) if strokes else None
    if strokes:
        engrave(objects['case-front'], strokes)
    stats = {}
    for name, obj in objects.items():
        triangulate_quantize(obj)
        stats[name] = require_mesh(obj)
        print(f"MESH {variant}/{name}: OK triangles={stats[name]['faces']} "
              f"components={stats[name]['connected_components']} non_manifold_edges={stats[name]['non_manifold_edges']} "
              f"degenerate_faces={stats[name]['degenerate_faces']}", flush=True)
    assembly = assembly_checks(objects)
    features = feature_checks(objects['case-front'])
    if art:
        art.pop('strokes')
        art['curvature_minima_mm'] = {st['name']: st['minimum_centerline_radius_mm'] for st in strokes}
        art.update(engraving_mesh_checks(objects['case-front'], strokes, uncarved_contact))
        print(f"ENGRAVING {variant}: OK width_lower_bound_mm={art['minimum_line_width_lower_bound_mm']:.6f} "
              f"min_centerline_radius_mm={min(art['curvature_minima_mm'].values()):.6f} "
              f"web_lower_bound_mm={art['minimum_solid_web_lower_bound_mm']:.6f} "
              f"chamfer_clearance_lower_bound_mm={art['minimum_chamfer_clearance_lower_bound_mm']:.6f} "
              f"remaining_face_wall_mm={art['minimum_remaining_face_wall_mm']:.6f} "
              f"depth_probes={art['depth_probe_count']}", flush=True)
    wall_summary = ','.join(format(w['surface_distance_mm'], '.6f') for w in walls.values())
    print(f"SAFETY {variant}: OK walls_mm={wall_summary} "
          f"assembly_overlap_mm3={assembly['front_back_overlap_mm3']:.9g} "
          'component_envelopes=OK root_ribs=OK snap_fits=OK functional_holes=OK', flush=True)

    (folder/'parts').mkdir(parents=True, exist_ok=True)
    comparisons = {}
    for name, obj in objects.items():
        path = folder/'parts'/f'{name}.stl'
        write_stl(path, print_vertices(obj, name), [tuple(p.vertices) for p in obj.data.polygons])
        vertices, triangles = read_stl(path)
        reread = obj_mesh('QA reimport', vertices, triangles)
        exported = require_mesh(reread)
        bpy.data.objects.remove(reread, do_unlink=True)
        assert abs(exported['bounds_mm'][2][0]) < .00001, (name, 'not on print bed', exported)
        assert abs(exported['volume_mm3']-stats[name]['volume_mm3']) < .01, (name, 'export volume changed')
        stats[name]['print_stl'] = stl_measurements(vertices, triangles)
        if check:
            comparisons[name] = compare_stl(path, HERE/f'dt1-{variant}'/'parts'/f'{name}.stl', f'{variant}/{name}')
    result = {'variant': variant, 'unit': 'millimeter', 'blender_version': bpy.app.version_string,
              'parts': stats, 'walls': walls, 'assembly': assembly, 'features': features}
    if art:
        result['engraving'] = art
    if check:
        result['comparison'] = comparisons
    (folder/'checks.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    ok = all(c['ok'] for c in comparisons.values())
    if blend and ok:
        bpy.data.orphans_purge(do_recursive=True)
        bpy.context.preferences.filepaths.save_version = 0
        bpy.ops.wm.save_as_mainfile(filepath=str(folder/f'hark-dt1-{variant}.blend'))
    print(f"BUILD {variant}: {'OK' if ok else 'DIFFERENT'} output={folder}", flush=True)
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', choices=('plain', 'carved', 'all'), required=True, help='简易款、谛听款或全部')
    parser.add_argument('--out', type=Path, help='输出根目录；默认新建临时目录，保留供检查')
    parser.add_argument('--check', action='store_true', help='与脚本旁 dt1-*/parts 中的 STL 严格比较，差异时返回失败')
    parser.add_argument('--blend', action='store_true', help='同时保存装配坐标下的 .blend')
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    variants = ('plain', 'carved') if args.variant == 'all' else (args.variant,)
    out = args.out.expanduser().resolve() if args.out else Path(tempfile.mkdtemp(prefix='hark-dt1-'))
    folders = {variant: output_paths(out, variant, args.blend) for variant in variants}
    print(f'BLENDER {bpy.app.version_string} OUTPUT {out}', flush=True)
    results = [build_variant(variant, folders[variant], args.check, args.blend) for variant in variants]
    if not all(results):
        raise RuntimeError('STL 与仓库成品不同；详情见 CHECK / DIFFERENCE 输出及输出目录的 checks.json。')
    print('DT1_BUILD_OK', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc()
        sys.exit(1)
