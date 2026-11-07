"""Ícones do inventário das katanas: a katana embainhada na diagonal (ponta para cima, à direita, como as espadas do
Minecraft), renderizada no Blender com fundo transparente e reduzida para 64 x 64.

    python icone.py            # todas (precisa do módulo bpy)
"""

import math
import os
import sys

import bpy
from mathutils import Vector

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)

from katanas import KATANAS  # noqa: E402

RAIZ = os.path.abspath(os.path.join(AQUI, "..", ".."))
MODELOS = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "model", "katana")
ICONES = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "textures", "items", "katana")


def render(nome):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.samples = 64
    sc.cycles.device = "CPU"
    sc.render.resolution_x = sc.render.resolution_y = 512
    sc.render.film_transparent = True
    world = bpy.data.worlds.new("w")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.5, 0.52, 0.56, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 1.2
    sc.world = world
    obj = os.path.join(MODELOS, nome + ".obj")
    tex = os.path.join(MODELOS, nome + ".png")
    bpy.ops.wm.obj_import(filepath=obj, use_split_groups=True, forward_axis="NEGATIVE_Z", up_axis="Y")
    mat = bpy.data.materials.new("m")
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    t = nt.nodes.new("ShaderNodeTexImage")
    t.image = bpy.data.images.load(tex)
    t.interpolation = "Closest"
    nt.links.new(t.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(t.outputs["Alpha"], bsdf.inputs["Alpha"])
    bsdf.inputs["Roughness"].default_value = 0.5
    luz = bpy.data.materials.new("l")
    luz.use_nodes = True
    ln = luz.node_tree
    for n in list(ln.nodes):
        if n.type != "OUTPUT_MATERIAL":
            ln.nodes.remove(n)
    out = [n for n in ln.nodes if n.type == "OUTPUT_MATERIAL"][0]
    lt = ln.nodes.new("ShaderNodeTexImage")
    lt.image = t.image
    em = ln.nodes.new("ShaderNodeEmission")
    em.inputs["Strength"].default_value = 2.0
    tr = ln.nodes.new("ShaderNodeBsdfTransparent")
    add = ln.nodes.new("ShaderNodeAddShader")
    ln.links.new(lt.outputs["Color"], em.inputs["Color"])
    ln.links.new(em.outputs[0], add.inputs[0])
    ln.links.new(tr.outputs[0], add.inputs[1])
    ln.links.new(add.outputs[0], out.inputs["Surface"])
    keep = []
    for o in list(sc.objects):
        if o.type != "MESH":
            continue
        base = o.name.split(".")[0]
        if base not in ("blade", "sheath", "blade_luminous", "sheath_luminous"):
            bpy.data.objects.remove(o, do_unlink=True)
            continue
        o.data.materials.clear()
        o.data.materials.append(luz if base.endswith("_luminous") else mat)
        keep.append(o)
    # todos num pai girado: a ponta (-X) para cima e à direita
    pai = bpy.data.objects.new("pai", None)
    sc.collection.objects.link(pai)
    for o in keep:
        o.parent = pai
    pai.rotation_euler = (0, math.radians(135), 0)
    centro = pai.rotation_euler.to_matrix() @ Vector((-125.0, 0.0, 2.0))
    pai.location = -centro
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    sc.collection.objects.link(cam)
    cam.location = (0, -600, 0)
    cam.rotation_euler = (math.radians(90), 0, 0)
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 250
    cam.data.clip_end = 5000
    sc.camera = cam
    for loc, energy in (((200, -300, 400), 4.0), ((-300, -200, 100), 1.5)):
        sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
        sun.data.energy = energy
        sun.location = loc
        sun.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        sc.collection.objects.link(sun)
    alto = os.path.join(ICONES, nome + "_512.png")
    sc.render.filepath = alto
    bpy.ops.render.render(write_still=True)
    return alto


def main():
    os.makedirs(ICONES, exist_ok=True)
    from PIL import Image, ImageFilter
    nomes = sys.argv[1:] or list(KATANAS)
    for n in nomes:
        alto = render(n)
        img = Image.open(alto).convert("RGBA")
        bbox = img.getbbox()
        if bbox:
            # centraliza num quadrado com uma margem
            w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
            lado = int(max(w, h) * 1.06)
            quad = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
            quad.paste(img.crop(bbox), ((lado - w) // 2, (lado - h) // 2))
            img = quad
        pequeno = img.resize((64, 64), Image.LANCZOS).filter(ImageFilter.UnsharpMask(radius=1, percent=60, threshold=2))
        pequeno.save(os.path.join(ICONES, n + ".png"), optimize=True)
        os.remove(alto)
        print("ícone", n)


main()
