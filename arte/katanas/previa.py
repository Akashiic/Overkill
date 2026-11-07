"""Renders de prévia de uma katana gerada (OBJ + PNG), no Blender (módulo bpy), sem tela.

    python previa.py katana.obj katana.png saida.png [grupos...]

Monta uma folha com vistas: espada inteira de lado, detalhe da guarda e do cabo, ponta, bainha, e a espada embainhada.
"""

import math
import sys

import bpy
from mathutils import Vector


def limpar():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def material(textura):
    mat = bpy.data.materials.new("katana")
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    if textura:
        tex = nt.nodes.new("ShaderNodeTexImage")
        tex.image = bpy.data.images.load(textura)
        tex.interpolation = "Closest"
        nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
        nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    else:
        bsdf.inputs["Base Color"].default_value = (0.6, 0.62, 0.66, 1)
    bsdf.inputs["Roughness"].default_value = 0.55
    bsdf.inputs["Metallic"].default_value = 0.0
    return mat


def material_luz(textura):
    """O passe aditivo do motor: a textura somada como emissão, o resto transparente."""
    mat = bpy.data.materials.new("luz")
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        if n.type != "OUTPUT_MATERIAL":
            nt.nodes.remove(n)
    out = [n for n in nt.nodes if n.type == "OUTPUT_MATERIAL"][0]
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(textura, check_existing=True)
    em = nt.nodes.new("ShaderNodeEmission")
    em.inputs["Strength"].default_value = 2.5
    tr = nt.nodes.new("ShaderNodeBsdfTransparent")
    add = nt.nodes.new("ShaderNodeAddShader")
    nt.links.new(tex.outputs["Color"], em.inputs["Color"])
    nt.links.new(em.outputs[0], add.inputs[0])
    nt.links.new(tr.outputs[0], add.inputs[1])
    nt.links.new(add.outputs[0], out.inputs["Surface"])
    return mat


def importar(obj, grupos, mat, mat_luz=None):
    bpy.ops.wm.obj_import(filepath=obj, use_split_groups=True, forward_axis="NEGATIVE_Z", up_axis="Y")
    keep = []
    for o in list(bpy.context.scene.objects):
        if o.type != "MESH":
            continue
        base = o.name.split(".")[0]
        luz = base.endswith("_luminous")
        if grupos and (base[:-len("_luminous")] if luz else base) not in grupos or (luz and mat_luz is None):
            bpy.data.objects.remove(o, do_unlink=True)
            continue
        o.data.materials.clear()
        o.data.materials.append(mat_luz if luz else mat)
        keep.append(o)
    return keep


def camera_luz(alvo, dist, direcao, lente=50, orto=None):
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    bpy.context.scene.collection.objects.link(cam)
    d = Vector(direcao).normalized()
    cam.location = Vector(alvo) + d * dist
    cam.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    if orto:
        cam.data.type = "ORTHO"
        cam.data.ortho_scale = orto
    cam.data.lens = lente
    cam.data.clip_end = 5000
    bpy.context.scene.camera = cam
    return cam


def cena(res):
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.samples = 48
    sc.cycles.device = "CPU"
    sc.render.resolution_x, sc.render.resolution_y = res
    sc.render.film_transparent = False
    world = bpy.data.worlds.new("w")
    world.use_nodes = True
    bg = world.node_tree.nodes["Background"]
    bg.inputs["Color"].default_value = (0.32, 0.36, 0.42, 1)
    bg.inputs["Strength"].default_value = 0.9
    sc.world = world
    for loc, energy in (((200, -300, 400), 5.0), ((-300, 200, 200), 2.0)):
        sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
        sun.data.energy = energy
        sun.location = loc
        sun.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        sc.collection.objects.link(sun)


def render(caminho):
    bpy.context.scene.render.filepath = caminho
    bpy.ops.render.render(write_still=True)


def main():
    obj, tex, saida = sys.argv[1:4]
    vistas = [
        # nome, grupos, alvo (coords do Blender: x, -z_obj, y_obj), direção, ortho
        ("inteira", ["blade"], (-125, 0, 2), (0, -1, 0.0001), 360),
        ("cabo", ["blade"], (-5, 0, 3), (0.35, -1, 0.45), 95),
        ("ponta", ["blade"], (-262, 0, -18), (0.1, -1, 0.25), 70),
        ("embainhada", ["blade", "sheath"], (-125, 0, 2), (0.15, -1, 0.35), 360),
    ]
    tex = None if tex == "-" else tex
    for nome, grupos, alvo, direcao, orto in vistas:
        limpar()
        cena((1400, 520) if nome in ("inteira", "embainhada") else (700, 520))
        mat = material(tex)
        importar(obj, grupos, mat, material_luz(tex) if tex else None)
        camera_luz(alvo, 800, direcao, orto=orto)
        render(saida.replace(".png", "-" + nome + ".png"))


main()
