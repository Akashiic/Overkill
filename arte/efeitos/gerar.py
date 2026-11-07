"""Gera os efeitos do Overkill (modelos e texturas próprios) no formato que o motor de efeitos do Künftig lê.

O motor é o do SlashBlade (portado do Resharped, MIT); os modelos daqui seguem só as convenções funcionais dele
(grupos, eixos, raios, o anel do corte com o alfa zero nas bordas, a rampa da máscara do anel de vida), com geometria
e texturas nossas:

* corte (slash):   anel r 54..100 no plano XZ, seção em lente, U = ângulo, V = raio (0 fora, 0,188 dentro); a textura
                   é varrida na vertical pelo motor durante a vida do corte;
* julgamento:      esfera r 100 (grupo base) e o crescente de vento no plano XY abaulado para +Y (grupo wind);
* onda (drive):    o crescente no plano XZ abaulado para +Z (grupo base);
* espada:          a espada invocada ao longo de +Z, ponta em z = +300 (grupo ss): aqui uma katana espectral;
* mira (lock-on):  colchetes (lockonBase), o anel de vida em gomos (lockonHealth) e a rampa que o esconde
                   (lockonHealthMask), no plano XY;
* rank:            o HUD no layout do rank.png do SlashBlade (letras D..SSS em 64x32, a variante "texto", a barra),
                   em 512x512 (o dobro: mais nítido em GUI grande);
* contador:        os dígitos e a palavra HITS do contador de golpes (estilo DmC).

    python gerar.py
"""

import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(AQUI, "..", "katanas"))

from geometria import Mesh, normalize  # noqa: E402
from textura import ruido  # noqa: E402

RAIZ = os.path.abspath(os.path.join(AQUI, "..", ".."))
SAIDA = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "model", "efeito")
GUI = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "textures", "gui")


def salvar_png(arr, caminho):
    """arr: (h, w, 4) em 0..1."""
    Image.fromarray((np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA").save(caminho, optimize=True)


def cabecalho(nome):
    return "Overkill: efeito %s\nGerado por arte/efeitos/gerar.py (arte própria do Overkill)." % nome


# ---- corte ---------------------------------------------------------------------------------------------------------
def corte():
    m = Mesh()
    N = 72
    # seção em lente: (raio, meia-altura, v)
    secao = [(54.4, 0.0, 0.188), (62.0, 2.3, 0.172), (69.5, 3.4, 0.156), (77.0, 4.3, 0.125), (84.9, 4.75, 0.094),
             (92.5, 3.2, 0.047), (100.0, 0.0, 0.0)]
    for lado in (1, -1):
        P, UV = [], []
        for i in range(N + 1):
            a = -math.pi + 2 * math.pi * i / N
            # U decresce com o ângulo (como o do SlashBlade); a textura repete, então não há costura
            u = 0.801 - i / N
            row, urow = [], []
            for r, h, v in secao:
                row.append((r * math.cos(a), lado * h, r * math.sin(a)))
                urow.append((u, v))
            P.append(row)
            UV.append(urow)
        # a costura do U: a coluna onde u volta de 0 para 1 ganha uma cópia
        m.grid("base", P, UV, outward=lambda i, p: np.array([p[0], 0.0, p[2]]))
    m.write_obj(os.path.join(SAIDA, "corte.obj"), cabecalho("corte"))
    # textura: rastros finos (fios de energia) ao longo do ângulo, um núcleo afiado, vento em volta
    S = 1024
    tex = np.zeros((S, S))
    rng = np.random.default_rng(3)
    uu, tt = np.meshgrid(np.linspace(0, 1, S), np.linspace(0, 1, S))
    fluxo = ruido(S, S, 6, 160, 4, 4)
    for _ in range(46):
        tc = rng.uniform(0.14, 0.76)
        sig = rng.uniform(0.004, 0.022)
        u0 = rng.uniform(0.02, 0.35)
        u1 = rng.uniform(0.62, 0.97)
        incl = rng.uniform(-0.08, 0.08)
        amp = rng.uniform(0.25, 0.75)
        cen = tc + incl * (uu - 0.5)
        corpo = np.exp(-((tt - cen) / sig) ** 2)
        afina = np.clip((uu - u0) / (u1 - u0), 0, 1)
        forma = np.where((uu > u0) & (uu < u1), np.sin(np.pi * afina) ** 0.6 * (0.35 + 0.65 * afina), 0)
        tex += amp * corpo * forma * (0.55 + 0.6 * fluxo)
    # o núcleo: a lâmina de luz, mais forte na frente (u alto)
    for tc, sig, amp in ((0.43, 0.006, 1.6), (0.47, 0.012, 0.8), (0.30, 0.008, 0.9), (0.60, 0.01, 0.7)):
        cen = tc + 0.05 * (uu - 0.5)
        afina = np.clip((uu - 0.12) / 0.8, 0, 1)
        forma = np.where((uu > 0.12) & (uu < 0.92), afina**1.5 * np.clip((0.95 - uu) / 0.05, 0, 1), 0)
        tex += amp * np.exp(-((tt - cen) / sig) ** 2) * forma
    # linhas de velocidade
    linhas = (ruido(S, S, 1.2, 300, 9, 2) > 0.8) * (tt > 0.15) * (tt < 0.78) * np.clip((uu - 0.2) * 2, 0, 1) * np.clip((0.9 - uu) * 6, 0, 1)
    tex += 0.25 * linhas
    tex = np.clip(tex, 0, 1)
    img = np.stack([tex, tex, tex, tex], -1)
    salvar_png(img, os.path.join(SAIDA, "corte.png"))


# ---- crescentes (vento do julgamento, onda) ---------------------------------------------------------------------------
def crescente(m, grupo, plano, regiao_uv):
    """Crescente com chifres em x = +-97, borda interna no ápice 80 e externa 130, espessura 7,4 no meio.
    plano 'xy': abaulado para +Y, espessura em Z; 'xz': abaulado para +Z, espessura em Y."""
    N = 40
    u0, v0, u1, v1 = regiao_uv
    camadas = [(0.0, 0.0), (0.25, 0.8), (0.5, 1.0), (0.75, 0.8), (1.0, 0.0)]  # da borda interna à externa
    for lado in (1, -1):
        P, UV = [], []
        for i in range(N + 1):
            t = -1 + 2 * i / N
            ang = t * math.radians(70)
            row, urow = [], []
            for f, esp in camadas:
                # borda interna e externa como arcos que se encontram nos chifres
                cx = 97 * math.sin(ang) / math.sin(math.radians(70))
                inner = 80 - 48 * (1 - math.cos(ang)) / (1 - math.cos(math.radians(70)))
                outer = 130 - 92 * (1 - math.cos(ang)) / (1 - math.cos(math.radians(70)))
                outer = max(outer, inner + 0.5)
                y = inner + f * (outer - inner)
                z = lado * 7.4 * esp * (1 - abs(t) ** 2)
                if plano == "xy":
                    row.append((cx, y, z))
                else:
                    row.append((cx, z, y))
                urow.append((u0 + (u1 - u0) * (0.5 + 0.5 * t), v0 + (v1 - v0) * f))
            P.append(row)
            UV.append(urow)
        if plano == "xy":
            m.grid(grupo, P, UV, outward=lambda i, p: np.array([p[0], p[1], 0.0]))
        else:
            m.grid(grupo, P, UV, outward=lambda i, p: np.array([p[0], 0.0, p[2]]))


def esfera(m, grupo, r, nu, nv, regiao_uv):
    u0, v0, u1, v1 = regiao_uv
    P, UV = [], []
    for i in range(nv + 1):
        th = math.pi * i / nv
        row, urow = [], []
        for j in range(nu + 1):
            ph = 2 * math.pi * j / nu
            row.append((r * math.sin(th) * math.cos(ph), r * math.cos(th), r * math.sin(th) * math.sin(ph)))
            urow.append((u0 + (u1 - u0) * j / nu, v0 + (v1 - v0) * i / nv))
        P.append(row)
        UV.append(urow)
    m.grid(grupo, P, UV, outward=lambda i, p: np.zeros(3))


def julgamento():
    m = Mesh()
    # a esfera usa um canto branco liso da textura; o vento, a faixa com o alfa subindo dos chifres para o meio
    esfera(m, "base", 100, 32, 18, (0.80, 0.80, 0.97, 0.97))
    crescente(m, "wind", "xy", (0.04, 0.70, 0.70, 0.30))
    m.write_obj(os.path.join(SAIDA, "julgamento.obj"), cabecalho("julgamento"))
    S = 256
    tex = np.zeros((S, S, 4))
    tex[int(0.03 * S):int(0.2 * S), int(0.80 * S):int(0.97 * S)] = 1.0  # branco liso (com o V do OBJ invertido)
    uu, vv = np.meshgrid(np.linspace(0, 1, S), np.linspace(0, 1, S))
    # vento: em pixels, v do OBJ 0,30..0,70 vira linhas 0,30..0,70 (1 - v)
    fu = np.clip((uu - 0.04) / 0.66, 0, 1)
    fv = np.clip(((1 - vv) - 0.70) / (0.30 - 0.70), 0, 1)  # 0 na borda interna, 1 na externa
    dentro = (uu > 0.04) & (uu < 0.70) & (vv > 0.30) & (vv < 0.70)
    meio = np.sin(np.pi * fu) ** 1.4
    fios = 0.7 + 0.3 * ruido(S, S, 2, 40, 12, 3)
    borda = np.exp(-((fv - 0.85) / 0.12) ** 2) + 0.4
    a = np.where(dentro, np.clip(meio * borda * fios * 0.78, 0.04, 0.78), 0)
    tex[..., 0:3] = np.where(dentro[..., None], 1.0, tex[..., 0:3])
    tex[..., 3] = np.where(dentro, a, tex[..., 3])
    salvar_png(tex, os.path.join(SAIDA, "julgamento.png"))


def onda():
    m = Mesh()
    crescente(m, "base", "xz", (0.04, 0.70, 0.70, 0.30))
    m.write_obj(os.path.join(SAIDA, "onda.obj"), cabecalho("onda"))
    S = 256
    uu, vv = np.meshgrid(np.linspace(0, 1, S), np.linspace(0, 1, S))
    fu = np.clip((uu - 0.04) / 0.66, 0, 1)
    fv = np.clip(((1 - vv) - 0.70) / (0.30 - 0.70), 0, 1)
    dentro = (uu > 0.04) & (uu < 0.70) & (vv > 0.30) & (vv < 0.70)
    # a borda da frente (externa) mais forte, rastros para trás
    rast = 0.6 + 0.4 * ruido(S, S, 1.5, 30, 21, 3)
    lum = (0.45 + 0.55 * fv**2.5) * rast * np.sin(np.pi * fu) ** 0.5
    tex = np.zeros((S, S, 4))
    tex[..., 0:3] = np.where(dentro[..., None], np.clip(lum, 0, 1)[..., None] * 0.3 + 0.7, 0)
    tex[..., 3] = np.where(dentro, np.clip(lum, 0, 1), 0)
    salvar_png(tex, os.path.join(SAIDA, "onda.png"))


# ---- espada invocada: uma katana espectral ------------------------------------------------------------------------------
def espada():
    m = Mesh()
    # lâmina de -40 a +300 em z, curva leve para +x... o fio em +y; cabo até -198
    N = 30
    def centro(z):
        f = max(0.0, (z + 40) / 340)
        return np.array([-10 * f * f, 0.0, z])
    prof = [(1.0, 0.0), (0.5, 1.0), (-0.6, 0.85), (-1.0, 0.0)]  # (posição no fio->dorso, espessura relativa)
    for lado in (1, -1):
        P, UV = [], []
        for i in range(N + 1):
            f = i / N
            z = -40 + 340 * f
            larg = 12 * (1 - 0.35 * f) * (1 if f < 0.88 else math.sqrt(max(0.0, 1 - ((f - 0.88) / 0.12) ** 2)))
            larg = max(larg, 0.3)
            esp = 8.7 * (1 - 0.5 * f)
            c = centro(z)
            row, urow = [], []
            for k, (a, e) in enumerate(prof):
                row.append(c + np.array([a * larg, lado * e * esp, 0.0]))
                urow.append((0.05 + 0.4 * k / (len(prof) - 1), 0.03 + 0.94 * f))
            P.append(row)
            UV.append(urow)
        m.grid("ss", P, UV, outward=lambda i, p: centro(p[2]))
    # guarda (anel achatado) e cabo
    from katana import superellipse  # noqa: E402
    cont = superellipse(24, 30, 12, 2.4, 0.0, 0.0)
    for z0, z1 in ((-46, -40),):
        a0 = [np.array([yy, zz, z0]) for yy, zz in cont]
        a1 = [np.array([yy, zz, z1]) for yy, zz in cont]
        P = [a0 + [a0[0]], a1 + [a1[0]]]
        UV = [[(0.6 + 0.3 * k / 24, 0.1) for k in range(25)], [(0.6 + 0.3 * k / 24, 0.15) for k in range(25)]]
        m.grid("ss", P, UV, outward=lambda i, p: np.array([0.0, 0.0, p[2]]))
        for zz, sg in ((z0, -1), (z1, 1)):
            ring = [np.array([q[0], q[1], zz]) for q in a0]
            m.fan("ss", ring, np.array([0.0, 0.0, zz]), [(0.75, 0.12)] * len(ring), (0.75, 0.12), (0, 0, sg))
    cab = superellipse(14, 9, 6.5, 2.2, 0.0, 0.0)
    aneis = []
    for k in range(9):
        z = -46 - 152 * k / 8
        sc = 1.0 if k < 8 else 0.6
        aneis.append([np.array([yy * sc, zz * sc, z]) for yy, zz in cab])
    P = [a + [a[0]] for a in aneis]
    UV = [[(0.6 + 0.3 * j / 14, 0.2 + 0.7 * i / 8) for j in range(15)] for i in range(9)]
    m.grid("ss", P, UV, outward=lambda i, p: np.array([0.0, 0.0, p[2]]))
    m.fan("ss", aneis[-1], np.array([0.0, 0.0, -198.3]), [(0.75, 0.9)] * 14, (0.75, 0.9), (0, 0, -1))
    m.write_obj(os.path.join(SAIDA, "espada.obj"), cabecalho("espada"))
    # textura: o motor desenha opaca e aditiva na cor; aqui o brilho varia (fios claros, miolo mais fraco)
    S = 256
    uu, vv = np.meshgrid(np.linspace(0, 1, S), np.linspace(0, 1, S))
    tex = np.zeros((S, S, 4))
    lam = (uu > 0.03) & (uu < 0.47)
    fu = np.clip((uu - 0.05) / 0.4, 0, 1)
    fio = np.exp(-(fu / 0.12) ** 2) + np.exp(-((1 - fu) / 0.1) ** 2) * 0.6 + np.exp(-((fu - 0.33) / 0.05) ** 2) * 0.5
    lum = np.clip(0.42 + 0.58 * fio + 0.15 * ruido(S, S, 2, 30, 31, 2), 0, 1)
    tex[..., 0:3] = np.where(lam[..., None], lum[..., None], 0.75)
    tex[..., 3] = 1.0
    salvar_png(tex, os.path.join(SAIDA, "espada.png"))


# ---- mira do lock-on ------------------------------------------------------------------------------------------------
def faixa(m, grupo, pontos, largura, v0=0.1, v1=0.9, fechada=False):
    """Uma faixa plana (z = 0) ao longo de `pontos` (x, y), com a largura mapeada no V da textura (brilho suave)."""
    pts = [np.array(p, dtype=float) for p in pontos]
    if fechada:
        pts.append(pts[0])
    n = len(pts)
    P, UV = [], []
    comp = [0.0]
    for a, b in zip(pts, pts[1:]):
        comp.append(comp[-1] + np.linalg.norm(b - a))
    for i, p in enumerate(pts):
        t = normalize(pts[min(n - 1, i + 1)] - pts[max(0, i - 1)])
        nn = np.array([-t[1], t[0]])
        a = p - nn * largura / 2
        b = p + nn * largura / 2
        P.append([(a[0], a[1], 0.0), (b[0], b[1], 0.0)])
        UV.append([(comp[i] / comp[-1] * 0.9 + 0.05, v0), (comp[i] / comp[-1] * 0.9 + 0.05, v1)])
    m.grid(grupo, P, UV, flip=False)


def mira():
    m = Mesh()
    # quatro colchetes em V (pontas para o centro) entre r 36 e 58, e um losango pequeno no meio
    for k in range(4):
        a = math.pi / 4 + k * math.pi / 2
        c, s = math.cos(a), math.sin(a)
        ponta = (36 * c, 36 * s)
        perp = (-s, c)
        braco1 = (58 * c + 14 * perp[0], 58 * s + 14 * perp[1])
        braco2 = (58 * c - 14 * perp[0], 58 * s - 14 * perp[1])
        faixa(m, "lockonBase", [braco1, ponta, braco2], 7.5)
    faixa(m, "lockonBase", [(0, 9), (9, 0), (0, -9), (-9, 0)], 3.4, fechada=True)
    # o anel de vida: doze gomos de 25 graus (5 de vão), r 82..98
    for k in range(12):
        a0 = math.radians(90 - k * 30 - 2.5)
        pts = [(90 * math.cos(a0 - math.radians(25) * j / 6), 90 * math.sin(a0 - math.radians(25) * j / 6)) for j in range(7)]
        faixa(m, "lockonHealth", pts, 14.0)
    # a máscara: uma rampa em espiral (z de +0,69 a -10,06 numa volta), cobrindo r 78..102 (a mecânica do SlashBlade)
    N = 48
    P, UV = [], []
    for i in range(N + 1):
        a = math.radians(90) - 2 * math.pi * i / N
        z = 0.69 + (-10.06 - 0.69) * i / N
        P.append([(78 * math.cos(a), 78 * math.sin(a), z), (102 * math.cos(a), 102 * math.sin(a), z)])
        UV.append([(0.5, 0.5), (0.5, 0.5)])
    m.grid("lockonHealthMask", P, UV, flip=False)
    # dupla face: o motor culla a máscara e o anel (como o SlashBlade); com a escala -Y do motor, desenhamos os dois lados
    for g in ("lockonHealthMask", "lockonHealth"):
        tris = list(m.groups[g])
        for a, b, c in tris:
            m.tri(g, a, c, b)
    m.write_obj(os.path.join(SAIDA, "mira.obj"), cabecalho("mira"))
    # textura: brilho suave através da faixa (núcleo claro, borda que some)
    W, H = 64, 32
    vv = np.linspace(0, 1, H)[:, None] * np.ones((1, W))
    core = np.exp(-((vv - 0.5) / 0.16) ** 2)
    halo = np.exp(-((vv - 0.5) / 0.38) ** 2) * 0.55
    lum = np.clip(core + halo, 0, 1)
    tex = np.stack([np.ones_like(lum), np.ones_like(lum), np.ones_like(lum), lum], -1)
    salvar_png(tex, os.path.join(SAIDA, "mira.png"))


# ---- HUD do rank e contador -------------------------------------------------------------------------------------------
FONTE = None


def fonte(tam, negrito=True):
    candidatos = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
    for c in candidatos:
        if os.path.exists(c):
            return ImageFont.truetype(c, tam)
    return ImageFont.load_default()


def letra_img(texto, w, h, preench, contorno, brilho=None, escala=2):
    """Uma letra do rank em (w, h) pixels finais, desenhada em `escala`x: preenchida e/ou só o contorno."""
    W, H = w * escala, h * escala
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    tam = int(H * (0.95 if len(texto) == 1 else 0.86 if len(texto) == 2 else 0.74))
    f = fonte(tam)
    bbox = d.textbbox((0, 0), texto, font=f, stroke_width=int(3 * escala))
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (W - tw) / 2 - bbox[0]
    y = (H - th) / 2 - bbox[1]
    if brilho is not None:
        glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(glow).text((x, y), texto, font=f, fill=brilho, stroke_width=int(5 * escala), stroke_fill=brilho)
        img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(3 * escala)))
        d = ImageDraw.Draw(img)
    if preench is not None:
        d.text((x, y), texto, font=f, fill=preench, stroke_width=int(2 * escala), stroke_fill=contorno)
    else:
        # só o contorno: o traço grosso menos o miolo, e o miolo escuro translúcido
        traco = Image.new("L", (W, H), 0)
        ImageDraw.Draw(traco).text((x, y), texto, font=f, fill=255, stroke_width=int(2 * escala), stroke_fill=255)
        miolo = Image.new("L", (W, H), 0)
        ImageDraw.Draw(miolo).text((x, y), texto, font=f, fill=255)
        t = np.asarray(traco).astype(np.float32) / 255
        mi = np.asarray(miolo).astype(np.float32) / 255
        borda = np.clip(t - mi, 0, 1)
        base = np.asarray(img).astype(np.float32) / 255
        rgb = np.array(contorno[:3], np.float32) / 255
        escuro = np.array([20, 20, 26], np.float32) / 255
        out_rgb = borda[..., None] * rgb + mi[..., None] * escuro
        out_a = np.clip(borda + mi * 0.47, 0, 1)
        a0 = base[..., 3:4]
        comp_a = out_a[..., None] + a0 * (1 - out_a[..., None])
        comp_rgb = (out_rgb * out_a[..., None] + base[..., :3] * a0 * (1 - out_a[..., None])) / np.maximum(comp_a, 1e-6)
        img = Image.fromarray((np.concatenate([comp_rgb, comp_a], -1) * 255).astype(np.uint8), "RGBA")
    return img


def gradiente_metal(img, cima, baixo):
    """Pinta o preenchimento da letra com um degradê vertical de metal (mantendo o alfa)."""
    a = np.asarray(img).astype(np.float32) / 255
    h = a.shape[0]
    t = np.linspace(0, 1, h)[:, None, None]
    cor = np.array(cima)[None, None, :] / 255 * (1 - t) + np.array(baixo)[None, None, :] / 255 * t
    faixa_clara = np.exp(-((t - 0.38) / 0.06) ** 2) * 0.35
    rgb = np.clip(cor + faixa_clara, 0, 1)
    out = a.copy()
    sel = a[..., 3] > 0
    out[..., :3] = np.where(sel[..., None], rgb * (a[..., :3] > 0.5).astype(np.float32) + a[..., :3] * (a[..., :3] <= 0.5), a[..., :3])
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8), "RGBA")


RANKS = ["D", "C", "B", "A", "S", "SS", "SSS"]
CORES = [((200, 210, 220), (110, 120, 132)), ((190, 220, 255), (80, 120, 190)), ((150, 255, 200), (40, 150, 100)),
         ((255, 240, 150), (200, 140, 40)), ((255, 190, 120), (210, 90, 30)), ((255, 140, 120), (200, 40, 40)),
         ((255, 120, 150), (190, 20, 60))]


def rank_hud():
    E = 2  # 512 x 512: o dobro do layout de 256
    img = Image.new("RGBA", (256 * E, 256 * E), (0, 0, 0, 0))
    for i, (r, (c1, c2)) in enumerate(zip(RANKS, CORES)):
        y = 32 * i * E
        # 0..64: a letra cheia (sobe com o progresso); 64..128: o contorno; 128..192 e 192..256: a variante "texto"
        cheia = gradiente_metal(letra_img(r, 64 * E, 32 * E, (255, 255, 255, 255), (16, 16, 20, 255), escala=1), c1, c2)
        contorno = letra_img(r, 64 * E, 32 * E, None, (230, 230, 236, 255), escala=1)
        txt_cheia = gradiente_metal(letra_img(r, 64 * E, 32 * E, (255, 255, 255, 255), (40, 6, 10, 255), brilho=(255, 60, 60, 160), escala=1), (255, 120, 110), (200, 20, 30))
        txt_contorno = letra_img(r, 64 * E, 32 * E, None, (255, 90, 90, 255), brilho=(255, 40, 40, 120), escala=1)
        for img_l, x in ((cheia, 0), (contorno, 64), (txt_cheia, 128), (txt_contorno, 192)):
            img.alpha_composite(img_l, (x * E, y))
    # a barra: moldura em v 240..256 (u 0..64), o preenchimento em v 224..240 (u 16..49)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((14 * E, 244 * E, 50 * E, 251 * E), radius=2 * E, outline=(230, 230, 236, 255), width=E, fill=(10, 10, 14, 150))
    grad = np.zeros((16 * E, 48 * E, 4), np.uint8)
    t = np.linspace(0, 1, 48 * E)[None, :]
    grad[..., 0] = (255 * (0.9 + 0.1 * t)).astype(np.uint8)
    grad[..., 1] = (255 * (0.25 + 0.45 * t)).astype(np.uint8)
    grad[..., 2] = (255 * 0.2 * np.ones_like(t)).astype(np.uint8)
    yy = np.arange(16 * E)[:, None]
    grad[..., 3] = np.where((yy >= 5 * E) & (yy < 10 * E), 255, 0).astype(np.uint8)
    img.alpha_composite(Image.fromarray(grad, "RGBA"), (16 * E, 224 * E))
    img.save(os.path.join(GUI, "rank.png"), optimize=True)


def contador():
    """Dígitos 0..9 e a palavra HITS, estilo DmC: cada dígito 32x48, a palavra 96x48, numa textura 512x64."""
    img = Image.new("RGBA", (512, 64), (0, 0, 0, 0))
    for k in range(10):
        dig = gradiente_metal(letra_img(str(k), 32, 48, (255, 255, 255, 255), (12, 12, 16, 255), brilho=(0, 0, 0, 110), escala=1), (255, 255, 255), (170, 176, 190))
        img.alpha_composite(dig, (k * 32, 0))
    hits = gradiente_metal(letra_img("HITS", 112, 40, (255, 255, 255, 255), (12, 12, 16, 255), brilho=(0, 0, 0, 110), escala=1), (255, 230, 160), (220, 120, 40))
    img.alpha_composite(hits, (320, 4))
    img.save(os.path.join(GUI, "contador.png"), optimize=True)


def main():
    os.makedirs(SAIDA, exist_ok=True)
    os.makedirs(GUI, exist_ok=True)
    corte()
    julgamento()
    onda()
    espada()
    mira()
    rank_hud()
    contador()
    print("efeitos gerados em", SAIDA, "e", GUI)


if __name__ == "__main__":
    main()
