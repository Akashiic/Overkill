"""As cinco katanas do Overkill: o desenho de cada uma (medidas, paleta e os padrões próprios).

Ordem de progressão: Aço (a inicial), Serpente, Sakurajima, Raijin e Kusanagi no Tsurugi (lendária). `cor` é a cor dos
efeitos (arco do corte, espadas invocadas) que o plugin usa para cada uma.
"""

import math

import numpy as np

from katana import Desenho
from textura import cor, mistura, ruido, suave

KATANAS = {}


def katana(d):
    KATANAS[d.nome] = d
    return d


# ---- padrões ---------------------------------------------------------------------------------------------------------


def escamas(a, b, tam_a, tam_b, seed=0):
    """Escamas sobrepostas em (a, b) (unidades do modelo). Devolve (borda 0..1, brilho 0..1, id aleatório)."""
    row = np.floor(b / tam_b)
    a2 = a / tam_a + 0.5 * (row % 2)
    col = np.floor(a2)
    fa = a2 - col - 0.5
    fb = b / tam_b - row
    # escama em arco: o centro fica embaixo, a borda é o arco de cima
    dist = np.sqrt((fa * 1.0) ** 2 + (fb * 1.15) ** 2)
    borda = np.exp(-((dist - 0.62) / 0.07) ** 2)
    brilho = np.clip(1 - dist / 0.62, 0, 1) * (fb > 0.15)
    rng = np.random.default_rng(seed)
    tab = rng.random(4096)
    ident = tab[((col * 73 + row * 151).astype(np.int64)) % 4096]
    return borda, brilho, ident


def rachaduras(h, w, ey, ex, seed, largura=0.025):
    """Rede de rachaduras: linhas finas onde um ruído cruza o meio (0..1)."""
    n = ruido(h, w, ey, ex, seed, 4, 0.55)
    n2 = ruido(h, w, ey * 0.5, ex * 0.5, seed + 1, 3)
    return np.clip(np.exp(-((n - 0.5) / largura) ** 2) + 0.6 * np.exp(-((n2 - 0.5) / largura) ** 2), 0, 1)


def raio(s_len, v_base, v_amp, seed, ramos=6):
    """Um relâmpago ao longo da lâmina: posição v para cada coluna, mais ramos curtos (listas de (s, v))."""
    rng = np.random.default_rng(seed)
    n = len(s_len)
    v = np.zeros(n)
    cur = v_base
    passo = max(1, n // 90)
    pts = []
    for i in range(0, n, passo):
        cur = np.clip(cur + rng.normal(0, v_amp), v_base - 2.5 * v_amp, v_base + 2.5 * v_amp)
        pts.append((i, cur))
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    v = np.interp(np.arange(n), xs, ys)
    galhos = []
    for _ in range(ramos):
        i0 = rng.integers(n // 10, n - n // 10)
        comp = rng.integers(n // 40, n // 14)
        dirv = rng.choice([-1, 1]) * rng.uniform(0.4, 1.0) * v_amp * 6
        seg = [(i0 + k, v[i0] + dirv * k / comp + rng.normal(0, v_amp * 0.4)) for k in range(comp)]
        galhos.append(seg)
    return v, galhos


def brilho_linha(FU, FV, cols, vcurve, largura):
    """Intensidade de uma linha v = vcurve(coluna) no mapa (FU, FV)."""
    idx = np.clip((FU[0] * (len(vcurve) - 1)).astype(int), 0, len(vcurve) - 1)
    vv = vcurve[idx][None, :]
    return np.exp(-((FV - vv) / largura) ** 2)


def flor(a, b, centros, raio_f):
    """Flores de cinco pétalas (sakura) em (a, b): intensidade 0..1 e a fase (0..1) de cada uma no comprimento."""
    out = np.zeros_like(a)
    fase = np.zeros_like(a)
    for (ca, cb, r, rot, ph) in centros:
        da, db = a - ca, b - cb
        rr = np.sqrt(da**2 + db**2)
        ang = np.arctan2(db, da) + rot
        perfil = r * (0.62 + 0.38 * np.abs(np.cos(2.5 * ang))) * (1 - 0.18 * (np.cos(5 * ang) > 0.96))
        m = 1 - suave(rr, perfil * 0.8, perfil)
        sel = m > out
        out = np.where(sel, m, out)
        fase = np.where(sel, ph, fase)
    return out, fase


# ---- 1. Aço (a inicial) ----------------------------------------------------------------------------------------------
katana(Desenho(
    nome="aco",
    textura={"titulo": "Katana de Aço", "hamon_tipo": "notare", "seed": 11, "cor": 0x3333FF},
))


# ---- 2. Serpente ------------------------------------------------------------------------------------------------------
def serpente_lamina(p, rgb, FU, FV, dentro, across):
    a = FU * 257.0
    b = FV * 11.0
    borda, brilho, _ = escamas(a, b, 2.6, 1.5, p.seed)
    ji = (1 - dentro)[..., None]
    rgb = rgb * (1 - 0.16 * borda[..., None] * ji) + 0.05 * brilho[..., None] * ji
    return rgb


def serpente_saya(p, rgb, FU, FV, ang):
    a = FU * 261.0
    b = FV * 31.0
    borda, brilho, ident = escamas(a, b, 2.3, 1.4, p.seed + 3)
    base = mistura(cor((18, 52, 32)) * np.ones_like(rgb), cor((40, 96, 58)) * np.ones_like(rgb), (0.3 + 0.5 * ident) * brilho)
    base = base * (1 - 0.55 * borda)[..., None]
    # mantém o reflexo da laca por cima
    return mistura(base, rgb, 0.35 * np.ones(FU.shape))


def serpente_tsuba(p, rgb, y, z, r):
    th = np.arctan2(z, y)
    # serpente enrolada: uma fita em espiral do aro ao centro
    espiral = ((r * 3.2 + th / (2 * np.pi)) % 1.0)
    corpo = suave(espiral, 0.08, 0.16) * (1 - suave(espiral, 0.5, 0.58)) * (r > 0.32) * (r < 0.86)
    borda, brilho, _ = escamas(th * 14, r * 30, 1.0, 0.7, p.seed + 5)
    cobra = cor((54, 92, 62)) * (0.8 + 0.5 * brilho[..., None]) * (1 - 0.4 * borda[..., None])
    rgb = mistura(rgb, cobra, corpo * 0.95)
    # sombra nas bordas do corpo (relevo)
    rgb *= (1 - 0.35 * np.exp(-((espiral - 0.12) / 0.03) ** 2) * (r > 0.32) * (r < 0.86))[..., None]
    # a cabeça perto do centro, com o olho dourado
    cabeca = np.exp(-(((y - 3.5) / 3.2) ** 2 + ((z - 3.5) / 2.2) ** 2))
    rgb = mistura(rgb, cor((60, 104, 70)) * np.ones_like(rgb), np.clip(cabeca * 1.4, 0, 1))
    olho = np.exp(-(((y - 4.6) / 0.6) ** 2 + ((z - 4.4) / 0.6) ** 2))
    rgb = mistura(rgb, cor((240, 200, 60)) * np.ones_like(rgb), np.clip(olho * 2, 0, 1))
    return rgb


katana(Desenho(
    nome="serpente",
    largura_base=10.2,
    largura_yokote=7.2,
    tsuba_ry=14.4, tsuba_rz=14.4, tsuba_e=2.0,
    textura={
        "titulo": "Katana da Serpente", "seed": 23, "cor": 0x33CC77,
        "hamon_tipo": "midare", "hamon_altura": 0.33,
        "aco_ji": (96, 122, 110), "aco_shinogi": (66, 86, 76), "aco_mune": (98, 118, 108),
        "hamon": (204, 226, 212), "nioi": (222, 246, 230), "fio": (226, 240, 232),
        "ito": (18, 58, 36), "ito_brilho": (72, 150, 100), "same": (206, 212, 186),
        "ferro": (38, 50, 42), "ferro_brilho": (110, 142, 118),
        "cobre": (170, 132, 54), "cobre_brilho": (246, 214, 128),
        "metal_cabo": (28, 42, 34), "metal_cabo_brilho": (102, 142, 112),
        "laca": (14, 40, 26), "laca_brilho": (86, 160, 116),
        "chifre": (22, 32, 26), "chifre_brilho": (90, 120, 100),
        "sageo": (22, 66, 40), "sageo_brilho": (92, 172, 122),
        "lamina_extra": serpente_lamina, "saya_extra": serpente_saya, "tsuba_extra": serpente_tsuba,
    },
))


# ---- 3. Sakurajima (o vulcão, com pétalas virando brasa) ----------------------------------------------------------------
def sakurajima_lamina(p, rgb, FU, FV, dentro, across):
    h, w = FU.shape
    veios = rachaduras(h, w, 10, 60, p.seed + 1, 0.03) * (1 - dentro)
    p.veios_lamina = veios
    rgb = mistura(rgb, cor((70, 26, 16)) * np.ones_like(rgb), veios * 0.85)
    # o hamon escuro avermelhado, de metal aquecido
    rgb = mistura(rgb, cor((128, 54, 34)) * (0.8 + 0.3 * ruido(h, w, 4, 40, p.seed + 2, 3))[..., None], dentro * 0.85)
    return rgb


def sakurajima_lamina_luz(p, FU, FV):
    h, w = FU.shape
    m = p.mascaras
    pulso = 0.75 + 0.25 * ruido(h, w, 3, 30, p.seed + 3, 3)
    hamon = m["dentro"] * pulso * 0.30 + m["nioi"] * 0.45
    veios = getattr(p, "veios_lamina", np.zeros_like(FU))
    luz = np.clip(hamon, 0, 1)[..., None] * cor((255, 104, 30)) + np.clip(veios * pulso, 0, 1)[..., None] * cor((255, 140, 40))
    return luz


def sakura_centros(seed, n, comp=261.0, volta=31.0):
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(n):
        ca = rng.random() ** 1.6 * comp  # mais flores perto da boca
        out.append((ca, rng.random() * volta, 1.3 + 0.8 * rng.random(), rng.random() * 6.28, ca / comp))
    return out


def sakurajima_saya(p, rgb, FU, FV, ang):
    a = FU * 261.0
    b = FV * 31.0
    fl, fase = flor(a, b, sakura_centros(p.seed + 4, 140), 1.5)
    rosa = cor((242, 168, 192))
    brasa = cor((150, 50, 20))
    tinta = mistura(rosa * np.ones_like(rgb), brasa * np.ones_like(rgb), suave(fase, 0.25, 0.7))
    rgb = mistura(rgb, tinta, fl * 0.9)
    # rachaduras de lava perto da ponteira
    h, w = FU.shape
    veios = rachaduras(h, w, 8, 50, p.seed + 5, 0.03) * suave(FU, 0.55, 1.0)
    p.veios_saya = veios
    p.flores_saya = (fl, fase)
    return mistura(rgb, cor((60, 20, 12)) * np.ones_like(rgb), veios * 0.8)


def sakurajima_saya_luz(p, FU, FV):
    fl, fase = getattr(p, "flores_saya", (np.zeros_like(FU), np.zeros_like(FU)))
    veios = getattr(p, "veios_saya", np.zeros_like(FU))
    brasa = fl * suave(fase, 0.35, 0.8)
    return np.clip(brasa * 0.9, 0, 1)[..., None] * cor((255, 110, 40)) + np.clip(veios, 0, 1)[..., None] * cor((255, 90, 20))


def sakurajima_tsuba(p, rgb, y, z, r):
    h, w = y.shape
    veios = rachaduras(h, w, 9, 9, p.seed + 6, 0.035) * (r < 0.9)
    p.veios_tsuba = veios
    return mistura(rgb, cor((60, 20, 10)) * np.ones_like(rgb), veios * 0.85)


def sakurajima_tsuba_luz(p, FU, FV):
    veios = getattr(p, "veios_tsuba", None)
    if veios is None or veios.shape != FU.shape:
        from PIL import Image
        src = getattr(p, "veios_tsuba", np.zeros((8, 8)))
        veios = np.asarray(Image.fromarray(src.astype(np.float32), mode="F").resize(FU.shape[::-1]))
    return np.clip(veios, 0, 1)[..., None] * cor((255, 100, 30))


katana(Desenho(
    nome="sakurajima",
    largura_base=10.8,
    largura_yokote=7.9,
    espessura_base=1.35,
    tsuba_ry=15.6, tsuba_rz=12.8, tsuba_e=2.6,
    textura={
        "titulo": "Katana Sakurajima", "seed": 37, "cor": 0xFF6A2A,
        "hamon_tipo": "gunome", "hamon_altura": 0.30,
        "aco_ji": (62, 56, 60), "aco_shinogi": (42, 38, 42), "aco_mune": (64, 58, 62),
        "hamon": (128, 54, 34), "nioi": (255, 140, 70), "fio": (226, 188, 160),
        "ito": (104, 18, 22), "ito_brilho": (204, 74, 60), "same": (44, 38, 38),
        "ferro": (34, 30, 30), "ferro_brilho": (92, 74, 66),
        "metal_cabo": (38, 28, 26), "metal_cabo_brilho": (130, 90, 80),
        "laca": (14, 10, 10), "laca_brilho": (116, 74, 74),
        "chifre": (30, 20, 18), "chifre_brilho": (120, 84, 70),
        "ouro": (190, 108, 48), "ouro_brilho": (255, 190, 130),
        "sageo": (116, 22, 28), "sageo_brilho": (210, 90, 90),
        "lamina_extra": sakurajima_lamina, "saya_extra": sakurajima_saya, "tsuba_extra": sakurajima_tsuba,
        "lamina_luz": sakurajima_lamina_luz, "saya_luz": sakurajima_saya_luz, "tsuba_luz": sakurajima_tsuba_luz,
        "luz": ("lamina", "saya", "tsuba"),
    },
))


# ---- 4. Raijin (o deus do trovão) --------------------------------------------------------------------------------------
def raijin_lamina_luz(p, FU, FV):
    h, w = FU.shape
    m = p.mascaras
    linha = m["linha"]
    v_base = float(np.mean(linha)) * m["v_shin"] / 0.66 + 0.12
    v, galhos = raio(np.arange(w), v_base, 0.012, p.seed + 8, ramos=10)
    core = brilho_linha(FU, FV, None, v, 0.006)
    glow = brilho_linha(FU, FV, None, v, 0.035)
    for seg in galhos:
        cols = np.array([c for c, _ in seg if 0 <= c < w])
        vals = np.array([vv for c, vv in seg if 0 <= c < w])
        for c, vv in zip(cols, vals):
            core[:, c] += np.exp(-((FV[:, c] - vv) / 0.005) ** 2) * 0.8
            glow[:, c] += np.exp(-((FV[:, c] - vv) / 0.025) ** 2) * 0.5
    # some e volta ao longo da lâmina (não é uma linha contínua)
    pulso = suave(ruido(1, w, 1, 12, p.seed + 9, 2)[0], 0.35, 0.6)[None, :]
    luz = np.clip(core * pulso, 0, 1)[..., None] * cor((230, 244, 255)) + np.clip(glow * pulso * 0.7, 0, 1)[..., None] * cor((70, 150, 255))
    return luz


def raijin_saya(p, rgb, FU, FV, ang):
    h, w = FU.shape
    # relâmpagos dourados em ziguezague ao longo da bainha, nos dois lados
    out = rgb.copy()
    marcas = np.zeros_like(FU)
    for k, centro in enumerate((0.25, 0.75)):
        v, _ = raio(np.arange(w), centro, 0.02, p.seed + 10 + k, ramos=0)
        marcas += brilho_linha(FU, FV, None, v, 0.012)
    p.marcas_saya = np.clip(marcas, 0, 1)
    return mistura(out, cor((206, 160, 60)) * np.ones_like(rgb), np.clip(marcas * 1.4, 0, 1))


def raijin_saya_luz(p, FU, FV):
    marcas = getattr(p, "marcas_saya", np.zeros_like(FU))
    return np.clip(marcas * 0.45, 0, 1)[..., None] * cor((90, 160, 255))


def raijin_tsuba(p, rgb, y, z, r):
    # mitsudomoe: três vírgulas girando (o tambor do Raijin)
    th = np.arctan2(z, y)
    total = np.zeros_like(r)
    for k in range(3):
        a0 = 2 * np.pi * k / 3
        cy, cz = 0.42 * math.cos(a0), 0.42 * math.sin(a0)
        yy, zz = y / 14.0, z / 14.0
        cabeca = np.sqrt((yy - cy) ** 2 + (zz - cz) ** 2) < 0.2
        rel = (np.arctan2(zz, yy) - a0) % (2 * np.pi)
        cauda = (rel < 2.0) * (np.abs(np.sqrt(yy**2 + zz**2) - (0.42 + 0.22 * rel / 2.0)) < 0.09 * (1 - rel / 2.2))
        total = np.maximum(total, (cabeca | cauda).astype(float))
    total *= (r < 0.82)
    return mistura(rgb, cor((214, 168, 64)) * (0.85 + 0.3 * (1 - r))[..., None] * np.ones_like(rgb), total)


katana(Desenho(
    nome="raijin",
    largura_base=10.4,
    largura_yokote=7.6,
    tsuba_ry=15.0, tsuba_rz=13.6, tsuba_e=2.2, tsuba_lobos=4, tsuba_lobo_fundo=0.14,
    textura={
        "titulo": "Katana Raijin", "seed": 51, "cor": 0x55AAFF,
        "hamon_tipo": "gunome", "hamon_altura": 0.28,
        "aco_ji": (128, 142, 168), "aco_shinogi": (90, 100, 126), "aco_mune": (124, 136, 160),
        "hamon": (222, 232, 255), "nioi": (200, 226, 255), "fio": (236, 244, 255),
        "ito": (58, 38, 108), "ito_brilho": (150, 120, 224), "same": (232, 230, 238),
        "ferro": (30, 30, 44), "ferro_brilho": (128, 128, 170),
        "cobre": (186, 146, 58), "cobre_brilho": (250, 220, 130),
        "metal_cabo": (36, 34, 50), "metal_cabo_brilho": (140, 136, 180),
        "laca": (16, 20, 48), "laca_brilho": (104, 124, 206),
        "chifre": (20, 20, 30), "chifre_brilho": (100, 100, 140),
        "sageo": (58, 38, 108), "sageo_brilho": (150, 120, 224),
        "saya_extra": raijin_saya, "tsuba_extra": raijin_tsuba,
        "lamina_luz": raijin_lamina_luz, "saya_luz": raijin_saya_luz,
        "luz": ("lamina", "saya"),
    },
))


# ---- 5. Kusanagi no Tsurugi (lendária: reta, dois fios, ouro e branco) -------------------------------------------------
def kusanagi_lamina(p, rgb, FU, FV, dentro, across):
    # filete de ouro na aresta central e nuvens gravadas perto da base
    centro = np.exp(-((FV - 0.5) / 0.035) ** 2)
    rgb = mistura(rgb, cor((226, 182, 80)) * np.ones_like(rgb), np.clip(centro * 1.2, 0, 1) * (FU < 0.9))
    h, w = FU.shape
    nuvem = suave(ruido(h, w, 30, 30, p.seed + 1, 3), 0.62, 0.66) * (1 - suave(FU, 0.12, 0.2))
    return mistura(rgb, cor((210, 170, 80)) * np.ones_like(rgb), nuvem * 0.7)


def kusanagi_lamina_luz(p, FU, FV):
    centro = np.exp(-((FV - 0.5) / 0.03) ** 2) * (FU < 0.9)
    fios = np.exp(-(FV / 0.02) ** 2) + np.exp(-((1 - FV) / 0.02) ** 2)
    h, w = FU.shape
    pulso = 0.8 + 0.2 * ruido(h, w, 2, 20, p.seed + 2, 2)
    return np.clip(centro * 0.75 * pulso, 0, 1)[..., None] * cor((255, 214, 110)) + np.clip(fios * 0.25, 0, 1)[..., None] * cor((255, 246, 210))


def kusanagi_saya(p, rgb, FU, FV, ang):
    h, w = FU.shape
    nuvem = ruido(h, w, 16, 40, p.seed + 3, 3)
    contorno = np.exp(-((nuvem - 0.6) / 0.012) ** 2)
    miolo = suave(nuvem, 0.6, 0.62)
    ouro = cor((214, 170, 70)) * np.ones_like(rgb)
    rgb = mistura(rgb, ouro, np.clip(contorno * 0.9 + miolo * 0.25, 0, 1))
    # faixas de ouro perto da boca e da ponta
    faixa = (np.abs(FU - 0.06) < 0.012) | (np.abs(FU - 0.94) < 0.012)
    p.nuvem_saya = contorno
    return mistura(rgb, ouro, faixa.astype(float))


def kusanagi_saya_luz(p, FU, FV):
    c = getattr(p, "nuvem_saya", np.zeros_like(FU))
    return np.clip(c * 0.35, 0, 1)[..., None] * cor((255, 214, 120))


def kusanagi_tsuba(p, rgb, y, z, r):
    # o espelho (yata no kagami): um disco polido no centro com anéis
    th = np.arctan2(z, y)
    disco = 1 - suave(np.sqrt((y / 6.5) ** 2 + (z / 6.5) ** 2), 0.9, 1.0)
    aneis = (np.sin(r * 70) > 0.75) * (r > 0.45) * (r < 0.85)
    raios_ = (np.cos(th * 16) > 0.92) * (r > 0.5) * (r < 0.8)
    rgb = mistura(rgb, cor((255, 236, 170)) * np.ones_like(rgb), disco * 0.8)
    return rgb * (1 - 0.18 * (aneis | raios_))[..., None]


def kusanagi_tsuba_luz(p, FU, FV):
    y = (FU - 0.5) * 2
    z = (FV - 0.5) * 2
    rr = np.sqrt(y**2 + z**2)
    return np.clip(np.exp(-((rr - 0.36) / 0.03) ** 2) * 0.6, 0, 1)[..., None] * cor((255, 220, 130))


katana(Desenho(
    nome="kusanagi",
    perfil="tsurugi",
    largura_base=11.6,
    largura_yokote=9.4,
    espessura_base=1.45,
    espessura_yokote=1.05,
    yokote=0.9,
    tsuba_ry=17.5, tsuba_rz=9.5, tsuba_e=3.2,
    cabo_ry=5.6, cabo_rz=4.0,
    textura={
        "titulo": "Kusanagi no Tsurugi", "seed": 77, "cor": 0xFFD966,
        "hamon_tipo": "suguha", "hamon_altura": 0.16,
        "aco_ji": (186, 192, 204), "aco_shinogi": (170, 176, 190), "aco_mune": (180, 184, 196),
        "hamon": (242, 246, 252), "nioi": (255, 250, 232), "fio": (255, 255, 255),
        "ito": (226, 222, 210), "ito_brilho": (255, 255, 250), "same": (196, 160, 84),
        "ferro": (168, 126, 46), "ferro_brilho": (255, 222, 142),
        "cobre": (176, 132, 50), "cobre_brilho": (255, 226, 150),
        "metal_cabo": (168, 126, 46), "metal_cabo_brilho": (255, 222, 142),
        "laca": (226, 222, 212), "laca_brilho": (255, 255, 255),
        "chifre": (168, 126, 46), "chifre_brilho": (255, 222, 142),
        "ouro": (190, 146, 56), "ouro_brilho": (255, 232, 160),
        "sageo": (196, 156, 70), "sageo_brilho": (255, 236, 170),
        "lamina_extra": kusanagi_lamina, "saya_extra": kusanagi_saya, "tsuba_extra": kusanagi_tsuba,
        "lamina_luz": kusanagi_lamina_luz, "saya_luz": kusanagi_saya_luz, "tsuba_luz": kusanagi_tsuba_luz,
        "luz": ("lamina", "saya", "tsuba"),
    },
))
