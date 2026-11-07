"""Ícones dos materiais da forja, nossos: as seis pedras de afiar (toishi de duas camadas, a de cima na cor do tier, com
veios que brilham dos épicos para cima), o tamahagane (o aço-joia, um torrão metálico iridescente) renderizados no
Blender, e os quatro ofudas de proteção (talismã de papel com moldura, pinceladas e o selo do percentual) pintados em
2D. Tudo em 512 px reduzido para 64 x 64, como os ícones das katanas.

    python forja.py            # precisa do módulo bpy (e numpy, pillow)
"""

import math
import os
import random

import bpy
from mathutils import Vector
from PIL import Image, ImageDraw, ImageFilter

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.abspath(os.path.join(AQUI, "..", ".."))
SAIDA = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "textures", "items", "forja")

# tier: (cor da camada de cima, cor de baixo, cor dos veios, força dos veios, pontos de mineral)
def lin(r, g, b):
    """sRGB 0..255 -> linear."""
    def c(v):
        v /= 255.0
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    return (c(r), c(g), c(b))


PEDRAS = {
    "comum": (lin(150, 148, 140), lin(78, 70, 62), lin(0, 0, 0), 0.0, 0.0),
    "incomum": (lin(92, 160, 92), lin(58, 70, 48), lin(120, 255, 120), 0.0, 0.0),
    "rara": (lin(70, 115, 200), lin(40, 46, 70), lin(130, 200, 255), 0.0, 3.0),
    "epica": (lin(130, 70, 175), lin(52, 36, 64), lin(205, 120, 255), 3.0, 3.0),
    "lendaria": (lin(215, 150, 50), lin(84, 56, 26), lin(255, 205, 90), 5.0, 4.0),
    "mitica": (lin(185, 30, 55), lin(66, 16, 28), lin(255, 80, 150), 7.0, 5.0),
}


def cena():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    sc.cycles.samples = 96
    sc.cycles.device = "CPU"
    sc.render.resolution_x = sc.render.resolution_y = 512
    sc.render.film_transparent = True
    sc.view_settings.view_transform = "Standard"
    world = bpy.data.worlds.new("w")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.5, 0.52, 0.56, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.55
    sc.world = world
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    sc.collection.objects.link(cam)
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = 4.0
    d = Vector((0, -1, 1.25)).normalized() * 20
    cam.location = d
    cam.rotation_euler = (-d).to_track_quat("-Z", "Y").to_euler()
    sc.camera = cam
    for loc, energy in (((4, -3, 8), 2.6), ((-6, -2, 2), 0.5), ((0, 6, 3), 1.2)):
        sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN"))
        sun.data.energy = energy
        sun.location = loc
        sun.rotation_euler = (Vector((0, 0, 0)) - Vector(loc)).to_track_quat("-Z", "Y").to_euler()
        sc.collection.objects.link(sun)
    return sc


def no(nt, tipo, **inputs):
    n = nt.nodes.new(tipo)
    for k, v in inputs.items():
        n.inputs[k].default_value = v
    return n


def material_pedra(cima, baixo, veio, forca, pontos):
    m = bpy.data.materials.new("pedra")
    m.use_nodes = True
    nt = m.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    coord = nt.nodes.new("ShaderNodeTexCoord")
    # as duas camadas: pela altura local (z do objeto)
    sep = nt.nodes.new("ShaderNodeSeparateXYZ")
    nt.links.new(coord.outputs["Object"], sep.inputs[0])
    camada = nt.nodes.new("ShaderNodeMapRange")
    camada.inputs["From Min"].default_value = 0.0
    camada.inputs["From Max"].default_value = 0.03
    nt.links.new(sep.outputs["Z"], camada.inputs["Value"])
    # o grão
    grao = no(nt, "ShaderNodeTexNoise", Scale=60.0, Detail=8.0, Roughness=0.7)
    nt.links.new(coord.outputs["Object"], grao.inputs["Vector"])
    mancha = no(nt, "ShaderNodeTexNoise", Scale=4.0, Detail=3.0)
    nt.links.new(coord.outputs["Object"], mancha.inputs["Vector"])
    mix_c = nt.nodes.new("ShaderNodeMix")
    mix_c.data_type = "RGBA"
    mix_c.inputs["A"].default_value = (*baixo, 1)
    mix_c.inputs["B"].default_value = (*cima, 1)
    nt.links.new(camada.outputs[0], mix_c.inputs["Factor"])
    sombra = nt.nodes.new("ShaderNodeMix")
    sombra.data_type = "RGBA"
    sombra.blend_type = "MULTIPLY"
    sombra.inputs["Factor"].default_value = 0.35
    nt.links.new(mix_c.outputs["Result"], sombra.inputs["A"])
    nt.links.new(grao.outputs["Color"], sombra.inputs["B"])
    mancha_mix = nt.nodes.new("ShaderNodeMix")
    mancha_mix.data_type = "RGBA"
    mancha_mix.blend_type = "OVERLAY"
    mancha_mix.inputs["Factor"].default_value = 0.4
    nt.links.new(sombra.outputs["Result"], mancha_mix.inputs["A"])
    nt.links.new(mancha.outputs["Color"], mancha_mix.inputs["B"])
    nt.links.new(mancha_mix.outputs["Result"], bsdf.inputs["Base Color"])
    # a face de cima, molhada e lisa; o resto áspero
    rough = nt.nodes.new("ShaderNodeMapRange")
    rough.inputs["To Min"].default_value = 0.85
    rough.inputs["To Max"].default_value = 0.28
    nt.links.new(camada.outputs[0], rough.inputs["Value"])
    nt.links.new(rough.outputs[0], bsdf.inputs["Roughness"])
    bump = no(nt, "ShaderNodeBump", Strength=0.25)
    nt.links.new(grao.outputs["Fac"], bump.inputs["Height"])
    nt.links.new(bump.outputs["Normal"], bsdf.inputs["Normal"])
    emissao = None
    if forca > 0:
        # veios: as bordas de um voronoi, só na camada de cima
        vor = no(nt, "ShaderNodeTexVoronoi", Scale=1.8)
        vor.feature = "DISTANCE_TO_EDGE"
        nt.links.new(coord.outputs["Object"], vor.inputs["Vector"])
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].position = 0.0
        ramp.color_ramp.elements[0].color = (1, 1, 1, 1)
        ramp.color_ramp.elements[1].position = 0.022
        ramp.color_ramp.elements[1].color = (0, 0, 0, 1)
        nt.links.new(vor.outputs["Distance"], ramp.inputs["Fac"])
        mult = no(nt, "ShaderNodeMath")
        mult.operation = "MULTIPLY"
        nt.links.new(ramp.outputs["Color"], mult.inputs[0])
        nt.links.new(camada.outputs[0], mult.inputs[1])
        emissao = mult
    if pontos > 0:
        pts = no(nt, "ShaderNodeTexVoronoi", Scale=40.0)
        nt.links.new(coord.outputs["Object"], pts.inputs["Vector"])
        r2 = nt.nodes.new("ShaderNodeValToRGB")
        r2.color_ramp.elements[0].position = 0.0
        r2.color_ramp.elements[0].color = (1, 1, 1, 1)
        r2.color_ramp.elements[1].position = 0.06
        r2.color_ramp.elements[1].color = (0, 0, 0, 1)
        nt.links.new(pts.outputs["Distance"], r2.inputs["Fac"])
        m2 = no(nt, "ShaderNodeMath")
        m2.operation = "MULTIPLY"
        m2.inputs[1].default_value = pontos
        nt.links.new(r2.outputs["Color"], m2.inputs[0])
        if emissao is None:
            emissao = m2
        else:
            soma = no(nt, "ShaderNodeMath")
            soma.operation = "ADD"
            nt.links.new(emissao.outputs[0], soma.inputs[0])
            nt.links.new(m2.outputs[0], soma.inputs[1])
            emissao = soma
    if emissao is not None:
        bsdf.inputs["Emission Color"].default_value = (*veio, 1)
        f = no(nt, "ShaderNodeMath")
        f.operation = "MULTIPLY"
        f.inputs[1].default_value = max(forca, 1.5)
        nt.links.new(emissao.outputs[0], f.inputs[0])
        nt.links.new(f.outputs[0], bsdf.inputs["Emission Strength"])
    return m


def pedra(tier):
    sc = cena()
    cima, baixo, veio, forca, pontos = PEDRAS[tier]
    bpy.ops.mesh.primitive_cube_add(size=1)
    o = bpy.context.active_object
    o.scale = (2.3, 1.0, 0.8)
    bpy.ops.object.transform_apply(scale=True)
    bev = o.modifiers.new("bev", "BEVEL")
    bev.width = 0.07
    bev.segments = 4
    sub = o.modifiers.new("sub", "SUBSURF")
    sub.levels = 2
    sub.render_levels = 3
    sub.subdivision_type = "SIMPLE"
    tex = bpy.data.textures.new("n", "CLOUDS")
    tex.noise_scale = 0.35
    random.seed(tier)
    disp = o.modifiers.new("disp", "DISPLACE")
    disp.texture = tex
    disp.strength = 0.035
    disp.mid_level = 0.5
    o.data.materials.append(material_pedra(cima, baixo, veio, forca, pontos))
    bpy.ops.object.shade_smooth()
    o.rotation_euler = (0, 0, math.radians(-38))
    return renderiza(sc, "pedra_" + tier)


def tamahagane():
    sc = cena()
    for i, (loc, s, seed) in enumerate((((0.0, 0.0, 0.0), 1.0, 1), ((0.95, -0.35, -0.28), 0.55, 2),
                                         ((-0.85, -0.45, -0.32), 0.45, 3))):
        bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=5, radius=0.95 * s, location=loc)
        o = bpy.context.active_object
        o.scale = (1.15, 0.9, 0.7)
        t = bpy.data.textures.new("v%d" % i, "VORONOI")
        t.noise_scale = 0.45 * s
        t.distance_metric = "DISTANCE"
        d = o.modifiers.new("d", "DISPLACE")
        d.texture = t
        d.strength = 0.45 * s
        d.mid_level = 0.6
        t2 = bpy.data.textures.new("c%d" % i, "CLOUDS")
        t2.noise_scale = 0.2
        d2 = o.modifiers.new("d2", "DISPLACE")
        d2.texture = t2
        d2.strength = 0.08
        o.rotation_euler = (seed * 0.7, seed * 1.3, seed * 0.4)
        m = bpy.data.materials.new("aco")
        m.use_nodes = True
        nt = m.node_tree
        b = nt.nodes["Principled BSDF"]
        b.inputs["Metallic"].default_value = 1.0
        b.inputs["Roughness"].default_value = 0.26
        # iridescência pela fresnel: azul -> violeta -> dourado nas bordas
        lw = no(nt, "ShaderNodeLayerWeight", Blend=0.45)
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        cr = ramp.color_ramp
        cr.elements[0].position = 0.0
        cr.elements[0].color = (*lin(150, 155, 168), 1)
        cr.elements[1].position = 1.0
        cr.elements[1].color = (*lin(220, 170, 90), 1)
        e = cr.elements.new(0.55)
        e.color = (*lin(105, 125, 190), 1)
        e = cr.elements.new(0.8)
        e.color = (*lin(175, 110, 205), 1)
        nt.links.new(lw.outputs["Facing"], ramp.inputs["Fac"])
        nt.links.new(ramp.outputs["Color"], b.inputs["Base Color"])
        b.inputs["Emission Color"].default_value = (0.7, 0.45, 1.0, 1)
        em = no(nt, "ShaderNodeMath")
        em.operation = "POWER"
        em.inputs[1].default_value = 6.0
        nt.links.new(lw.outputs["Facing"], em.inputs[0])
        f = no(nt, "ShaderNodeMath")
        f.operation = "MULTIPLY"
        f.inputs[1].default_value = 0.6
        nt.links.new(em.outputs[0], f.inputs[0])
        nt.links.new(f.outputs[0], b.inputs["Emission Strength"])
        o.data.materials.append(m)
        dec = o.modifiers.new("dec", "DECIMATE")
        dec.ratio = 0.18
        bpy.ops.object.shade_flat()
    return renderiza(sc, "tamahagane")


def renderiza(sc, nome):
    alto = os.path.join(SAIDA, nome + "_512.png")
    sc.render.filepath = alto
    bpy.ops.render.render(write_still=True)
    return alto


def reduz(alto, nome, margem=1.08):
    img = Image.open(alto).convert("RGBA")
    bbox = img.getbbox()
    if bbox:
        w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
        lado = int(max(w, h) * margem)
        quad = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
        quad.paste(img.crop(bbox), ((lado - w) // 2, (lado - h) // 2))
        img = quad
    pequeno = img.resize((64, 64), Image.LANCZOS).filter(ImageFilter.UnsharpMask(radius=1, percent=60, threshold=2))
    pequeno.save(os.path.join(SAIDA, nome + ".png"), optimize=True)
    if alto.endswith("_512.png"):
        os.remove(alto)


# ---- ofudas (2D) ---------------------------------------------------------------------------------------------------

OFUDAS = {
    "25": ((150, 110, 70), (120, 80, 50)),     # bronze
    "50": ((60, 160, 80), (30, 110, 50)),      # verde
    "75": ((60, 110, 210), (30, 70, 160)),     # azul
    "100": ((235, 180, 50), (190, 120, 20)),   # ouro
}

DIGITOS = {  # 3 x 5
    "0": ["111", "101", "101", "101", "111"],
    "1": ["010", "110", "010", "010", "111"],
    "2": ["111", "001", "111", "100", "111"],
    "5": ["111", "100", "111", "001", "111"],
    "7": ["111", "001", "010", "010", "010"],
}


def ofuda(pct):
    W, H = 512, 512
    cor, escura = OFUDAS[pct]
    rnd = random.Random(int(pct))
    papel = Image.new("RGBA", (180, 440), (0, 0, 0, 0))
    d = ImageDraw.Draw(papel)
    # papel com fibras
    d.rectangle((0, 0, 179, 439), fill=(236, 226, 196, 255))
    for _ in range(900):
        x, y = rnd.randrange(180), rnd.randrange(440)
        t = rnd.randrange(-18, 8)
        d.line((x, y, x + rnd.randrange(-6, 7), y + rnd.randrange(2, 9)), fill=(236 + t, 226 + t, 196 + t, 255))
    # moldura dupla vermelha
    d.rectangle((8, 8, 171, 431), outline=(170, 30, 30, 255), width=6)
    d.rectangle((20, 20, 159, 419), outline=(170, 30, 30, 255), width=2)
    # pinceladas (pseudo-caligrafia): traços grossos de ponta afinada
    def traco(x0, y0, x1, y1, w):
        n = 14
        for i in range(n):
            a = i / (n - 1)
            x = x0 + (x1 - x0) * a
            y = y0 + (y1 - y0) * a
            r = w * (1 - 0.7 * a) * (0.6 + 0.4 * math.sin(a * math.pi))
            d.ellipse((x - r, y - r, x + r, y + r), fill=(25, 20, 20, 255))
    # dois "caracteres" de pincel, diferentes entre si
    traco(52, 52, 128, 46, 10)
    traco(90, 40, 86, 120, 9)
    traco(60, 92, 76, 128, 8)
    traco(118, 88, 104, 128, 8)
    traco(50, 152, 130, 150, 9)
    traco(68, 140, 64, 214, 8)
    traco(112, 140, 116, 214, 8)
    traco(64, 182, 114, 180, 7)
    traco(56, 214, 126, 222, 9)
    # o selo: círculo na cor do tier com o percentual
    cx, cy, r = 90, 326, 72
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(*cor, 255), outline=(*escura, 255), width=6)
    d.ellipse((cx - r + 12, cy - r + 12, cx + r - 12, cy + r - 12), outline=(255, 245, 220, 200), width=3)
    px = 11 if len(pct) == 3 else 14
    largura = len(pct) * 3 * px + (len(pct) - 1) * px
    x0 = cx - largura // 2
    y0 = cy - 5 * px // 2
    for k, ch in enumerate(pct):
        for j, linha in enumerate(DIGITOS[ch]):
            for i, b in enumerate(linha):
                if b == "1":
                    x = x0 + k * 4 * px + i * px
                    yy = y0 + j * px
                    d.rectangle((x, yy, x + px - 1, yy + px - 1), fill=(255, 250, 235, 255))
    if pct == "100":
        # o de 100% brilha
        brilho = Image.new("RGBA", papel.size, (0, 0, 0, 0))
        bd = ImageDraw.Draw(brilho)
        bd.ellipse((cx - r - 14, cy - r - 14, cx + r + 14, cy + r + 14), fill=(255, 220, 120, 120))
        brilho = brilho.filter(ImageFilter.GaussianBlur(10))
        papel = Image.alpha_composite(brilho, papel)
    # curva leve: cada coluna desce um pouco (o papel arqueado)
    curvado = Image.new("RGBA", (180, 470), (0, 0, 0, 0))
    for x in range(180):
        off = int(14 * math.sin(x / 179 * math.pi))
        curvado.paste(papel.crop((x, 0, x + 1, 440)), (x, off))
    tela = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    rot = curvado.rotate(-28, expand=True, resample=Image.BICUBIC)
    sombra = Image.new("RGBA", rot.size, (0, 0, 0, 0))
    sombra.putalpha(rot.split()[3].point(lambda a: a * 0.45))
    sombra = sombra.filter(ImageFilter.GaussianBlur(8))
    ox, oy = (W - rot.width) // 2, (H - rot.height) // 2
    tela.alpha_composite(sombra, (ox + 10, oy + 12))
    tela.alpha_composite(rot, (ox, oy))
    alto = os.path.join(SAIDA, "ofuda_%s_512.png" % pct)
    tela.save(alto)
    return alto


def main():
    import sys
    os.makedirs(SAIDA, exist_ok=True)
    so = sys.argv[1:]
    if so == ["tamahagane"]:
        reduz(tamahagane(), "tamahagane")
        return
    for t in PEDRAS:
        reduz(pedra(t), "pedra_" + t)
        print("ícone pedra", t)
    reduz(tamahagane(), "tamahagane")
    print("ícone tamahagane")
    for p in OFUDAS:
        reduz(ofuda(p), "ofuda_" + p, margem=1.04)
        print("ícone ofuda", p)


main()
