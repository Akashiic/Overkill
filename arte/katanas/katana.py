"""Monta uma katana (Mesh) a partir de um Desenho: lâmina, habaki, seppa, tsuba, tsuka, saya, sageo e os grupos do
motor (quebrada, casca do brilho, ícones). O atlas da textura (ATLAS) é compartilhado com textura.py."""

import math
from dataclasses import dataclass, field

import numpy as np

from geometria import Mesh, normalize, superellipse

# ---- atlas 2048 x 1024: retângulos em pixels (x, y, largura, altura), y para baixo ---------------------------------
ATLAS_W, ATLAS_H = 2048, 1024
ATLAS = {
    "lamina": (0, 0, 2048, 112),        # lados da lâmina: ao longo (x) por fio -> dorso (y)
    "saya": (0, 128, 2048, 192),        # bainha: ao longo por volta
    "tsuka": (0, 336, 512, 256),        # cabo: ao longo (x) por volta (y)
    "tsuba": (528, 336, 256, 256),      # faces da guarda, planar (y, z)
    "tsuba_aro": (800, 336, 512, 32),   # borda da guarda: volta por espessura
    "habaki": (800, 384, 256, 128),     # volta por comprimento
    "fuchi": (1072, 384, 256, 64),      # colar do cabo
    "kashira": (1072, 456, 256, 64),    # pomo
    "koiguchi": (1344, 384, 512, 40),   # boca da bainha
    "boca": (1872, 384, 96, 48),        # a fenda escura da boca
    "kojiri": (1344, 440, 512, 40),     # ponteira da bainha
    "seppa": (800, 528, 128, 64),       # arruelas, planar
    "menuki": (944, 528, 128, 64),      # enfeites do cabo
    "kurikata": (1088, 528, 128, 64),   # botão da bainha
    "quebra": (1232, 528, 128, 64),     # superfície da quebra
    "sageo": (0, 608, 1024, 32),        # cordão: ao longo por volta
    "brilho": (0, 656, 2048, 32),       # casca do brilho de carga (o motor usa outra textura nela)
    "lamina_luz": (0, 696, 2048, 112),  # o que brilha na lâmina (preto não soma nada no passe aditivo)
    "saya_luz": (0, 816, 2048, 192),    # o que brilha na bainha
    "tsuba_luz": (1376, 528, 128, 128), # o que brilha na guarda
}


def uv(region, fu, fv):
    x, y, w, h = ATLAS[region]
    # meio texel de margem nas bordas da região
    px = x + 0.5 + np.clip(fu, 0, 1) * (w - 1)
    py = y + 0.5 + np.clip(fv, 0, 1) * (h - 1)
    return (px / ATLAS_W, 1 - py / ATLAS_H)


# ---- o desenho ------------------------------------------------------------------------------------------------------
@dataclass
class Desenho:
    nome: str
    # lâmina (medidas de encaixe do SlashBlade: base escondida em x0, ponta em x_ponta)
    x0: float = -34.0
    x_ponta: float = -291.0
    largura_base: float = 10.6
    largura_yokote: float = 7.6
    espessura_base: float = 1.25
    espessura_yokote: float = 0.8
    yokote: float = 0.885            # fração do comprimento onde começa a ponta (kissaki)
    curvatura: float = 1.0           # multiplica a sori
    perfil: str = "shinogi"          # "shinogi" (katana) ou "tsurugi" (reta, dois fios)
    # guarda
    tsuba_ry: float = 15.2
    tsuba_rz: float = 12.6
    tsuba_e: float = 2.3
    tsuba_lobos: int = 0             # 4: mokko (quatro lóbulos)
    tsuba_lobo_fundo: float = 0.0    # quanto o contorno entra entre os lóbulos
    # cabo
    cabo_ry: float = 5.4
    cabo_rz: float = 3.85
    ito_passo: float = 9.0
    # bainha
    saya_parede: float = 2.1
    saya_rz: float = 3.4
    sageo: bool = True
    lod: float = 1.0                 # detalhe da malha (os ícones usam uma versão mais leve)
    # cores e padrões: só textura.py lê
    textura: dict = field(default_factory=dict)


# perfil da lâmina: fração do fio (0) ao dorso (1) e meia-espessura relativa; dobras duras em SHINOGI e MUNE
PERFIL_A = [0.0, 0.05, 0.16, 0.32, 0.50, 0.66, 0.80, 0.92, 1.0]
PERFIL_Z = [0.0, 0.24, 0.55, 0.80, 0.94, 1.00, 0.86, 0.66, 0.0]
PAINEIS = [(0, 5), (5, 7), (7, 8)]  # ji (fio ao shinogi), shinogi-ji, mune (o telhado do dorso)
# tsurugi: dois fios, losango com uma aresta central
TSURUGI_A = [0.0, 0.08, 0.25, 0.5, 0.75, 0.92, 1.0]
TSURUGI_Z = [0.0, 0.35, 0.78, 1.0, 0.78, 0.35, 0.0]
TSURUGI_PAINEIS = [(0, 3), (3, 6)]


def perfil(d):
    if d.perfil == "tsurugi":
        return TSURUGI_A, TSURUGI_Z, TSURUGI_PAINEIS
    return PERFIL_A, PERFIL_Z, PAINEIS


def perfil_v(d):
    """Linha da textura (0..1) de cada ponto do perfil, pelo comprimento do perfil na base."""
    A, Z, _ = perfil(d)
    pts = [(a * d.largura_base, z * d.espessura_base) for a, z in zip(A, Z)]
    acc = [0.0]
    for p, q in zip(pts, pts[1:]):
        acc.append(acc[-1] + math.dist(p, q))
    return [a / acc[-1] for a in acc]


class Lamina:
    """A linha do dorso, a largura e a espessura ao longo de s (0 na base, 1 na ponta)."""

    def __init__(self, d):
        self.d = d

    def x(self, s):
        return self.d.x0 + s * (self.d.x_ponta - self.d.x0)

    def s_de_x(self, x):
        return (x - self.d.x0) / (self.d.x_ponta - self.d.x0)

    def dorso_y(self, x):
        # sori parecida com a do SlashBlade (para os golpes varrerem o mesmo arco), mas uma curva nossa, lisa
        t = -(x + 40.0)
        if self.d.perfil == "tsurugi":
            return 2.4 - self.d.largura_base / 2
        return -2.66 + self.d.curvatura * (0.0351 * t - 0.000592 * t * t)

    def quadro(self, s):
        """(ponto do dorso em x, y), normal unitária no plano xy para o fio (+y), largura, meia-espessura."""
        d = self.d
        x = self.x(s)
        h = 0.01
        y0, y1 = self.dorso_y(x - h), self.dorso_y(x + h)
        tang = normalize(np.array([-2 * h, y0 - y1]))  # para a ponta (-x)
        n = np.array([tang[1], -tang[0]])
        if n[1] < 0:
            n = -n
        if s <= d.yokote:
            f = s / d.yokote
            w = d.largura_base + f * (d.largura_yokote - d.largura_base)
            t = d.espessura_base + f * (d.espessura_yokote - d.espessura_base)
        else:
            u = (s - d.yokote) / (1 - d.yokote)
            k = math.sqrt(max(0.0, 1 - u * u)) if d.perfil != "tsurugi" else max(0.0, 1 - u) ** 0.9
            w = max(0.12, d.largura_yokote * k)
            t = max(0.03, d.espessura_yokote * (0.25 + 0.75 * k))
        b = np.array([x, self.dorso_y(x)])
        if d.perfil == "tsurugi":
            # dois fios: o centro fica na linha reta, a largura cresce para os dois lados
            b = np.array([x, 2.4 - w / 2])
        return b, n, w, t

    def ponto(self, s, a, zf, lado):
        b, n, w, t = self.quadro(s)
        xy = b + (1 - a) * w * n
        return np.array([xy[0], xy[1], lado * zf * t])

    def centro(self, s):
        b, n, w, _ = self.quadro(s)
        xy = b + 0.5 * w * n
        return np.array([xy[0], xy[1], 0.0])

    def anel(self, s, folga=0.0):
        """O contorno da seção (pontos únicos, fechado), afastado `folga` para fora (habaki, casca)."""
        A, Z, _ = perfil(self.d)
        pts = [self.ponto(s, a, z, 1) for a, z in zip(A, Z)]
        pts += [self.ponto(s, a, z, -1) for a, z in list(zip(A, Z))[-2:0:-1]]
        pts = np.array(pts)
        if folga:
            c = self.centro(s)
            out = []
            m = len(pts)
            for k in range(m):
                p0, p1, p2 = pts[k - 1], pts[k], pts[(k + 1) % m]
                e1 = normalize(np.array([0, p1[1] - p0[1], p1[2] - p0[2]]))
                e2 = normalize(np.array([0, p2[1] - p1[1], p2[2] - p1[2]]))
                nn = normalize(np.array([0, e1[2], -e1[1]]) + np.array([0, e2[2], -e2[1]]))
                if np.dot(nn, p1 - c) < 0:
                    nn = -nn
                out.append(p1 + folga * nn)
            pts = np.array(out)
        return pts


def secoes(d, de=0.0, ate=1.0):
    """Valores de s: uniformes até o yokote, mais densos na ponta."""
    lam = []
    n1 = max(10, int(38 * d.lod))
    for k in range(n1 + 1):
        lam.append(d.yokote * k / n1)
    K = max(6, int(16 * d.lod))
    for k in range(1, K + 1):
        u = math.sin(k / K * math.pi / 2)
        lam.append(d.yokote + u * (1 - d.yokote))
    out = [s for s in lam if de - 1e-9 <= s <= ate + 1e-9]
    if not out or out[0] > de + 1e-9:
        out.insert(0, de)
    if out[-1] < ate - 1e-9:
        out.append(ate)
    return out


# ---- partes ---------------------------------------------------------------------------------------------------------
def lamina(m, d, grupo, s_lista, quebra=None, regiao="lamina"):
    """Os painéis dos dois lados. `quebra`: deslocamentos de s por coluna na última (ou primeira) seção."""
    L = Lamina(d)
    A, Z, PAN = perfil(d)
    V = perfil_v(d)
    for (j0, j1) in PAN:
        for lado in (1, -1):
            P, UV = [], []
            for i, s in enumerate(s_lista):
                row, urow = [], []
                for j in range(j0, j1 + 1):
                    ss = s
                    if quebra is not None:
                        ss = quebra(i, j, s)
                    row.append(L.ponto(ss, A[j], Z[j], lado))
                    urow.append(uv(regiao, ss, V[j]))
                P.append(row)
                UV.append(urow)
            m.grid(grupo, P, UV, outward=lambda i, p, sl=s_lista: L.centro(sl[i]))
    return L


def tampa_quebra(m, d, grupo, s_col, direcao):
    """Fecha a lâmina na quebra (s_col[j] por coluna do perfil) com um leque."""
    L = Lamina(d)
    A, Z, _ = perfil(d)
    ring, uvs = [], []
    cols = list(range(len(A))) + list(range(len(A) - 2, 0, -1))
    sides = [1] * len(A) + [-1] * (len(A) - 2)
    for k, (j, lado) in enumerate(zip(cols, sides)):
        p = L.ponto(s_col[j], A[j], Z[j], lado)
        ring.append(p)
        ang = 2 * math.pi * k / len(cols)
        uvs.append(uv("quebra", 0.5 + 0.45 * math.cos(ang), 0.5 + 0.45 * math.sin(ang)))
    c = np.mean(ring, axis=0)
    m.fan(grupo, ring, c, uvs, uv("quebra", 0.5, 0.5), (direcao, 0, 0))


def tubo_aneis(m, grupo, aneis, uv_fn, centro_fn):
    """Superfície por anéis (lista de listas de pontos 3D, mesmo tamanho), fechada em volta."""
    n = len(aneis[0])
    P = [list(a) + [a[0]] for a in aneis]  # repete o primeiro ponto para a costura da UV
    UV = [[uv_fn(i, j / n) for j in range(n + 1)] for i in range(len(aneis))]
    m.grid(grupo, P, UV, outward=lambda i, p: centro_fn(i))


def anel_x(x, contorno):
    return [np.array([x, y, z]) for (y, z) in contorno]


def habaki(m, d, grupo):
    L = Lamina(d)
    xs = [-47.0, -42.0, -37.0]
    folgas = [0.85, 1.15, 1.45]
    aneis = [L.anel(L.s_de_x(x), f) for x, f in zip(xs, folgas)]
    tubo_aneis(m, grupo, aneis, lambda i, f: uv("habaki", f, i / (len(xs) - 1)), lambda i: L.centro(L.s_de_x(xs[i])))
    # a face da frente (para a ponta): da lâmina até o habaki
    s = L.s_de_x(xs[0])
    blade_ring = L.anel(s)
    n = len(blade_ring)
    m.strip(grupo, list(blade_ring), list(aneis[0]),
            [uv("habaki", k / n, 0.0) for k in range(n)], [uv("habaki", k / n, 0.08) for k in range(n)],
            np.array([-1.0, 0, 0]))


def prato(m, grupo, x0, x1, contorno, regiao_face, regiao_aro, ext, aro=None):
    """Peça plana oval de x0 a x1 (seppa, tsuba): duas faces e a borda. `aro`: (recuo, profundidade) da borda
    saliente nas faces (só a tsuba)."""
    n = len(contorno)
    cy = sum(p[0] for p in contorno) / n
    cz = sum(p[1] for p in contorno) / n

    def fuv(y, z):
        return uv(regiao_face, 0.5 + (y - cy) / (2 * ext), 0.5 + (z - cz) / (2 * ext))

    # borda (parede), normal radial
    borda(m, grupo, x0, x1, contorno, regiao_aro)
    for x, sign in ((x0, -1.0), (x1, 1.0)):
        if aro is None:
            ring = anel_x(x, contorno)
            m.fan(grupo, ring, np.array([x, cy, cz]), [fuv(p[1], p[2]) for p in ring], fuv(cy, cz), (sign, 0, 0))
        else:
            recuo, prof = aro
            inner = [(cy + (y - cy) * (1 - recuo), cz + (z - cz) * (1 - recuo)) for (y, z) in contorno]
            xin = x - sign * prof
            ring_o = anel_x(x, contorno)
            ring_i = anel_x(x, inner)
            ring_d = anel_x(xin, inner)
            m.strip(grupo, ring_o, ring_i, [fuv(p[1], p[2]) for p in ring_o], [fuv(p[1], p[2]) for p in ring_i],
                    np.array([sign, 0, 0]))
            # degrau do aro para o miolo (parede interna, voltada para o centro)
            for k in range(n):
                k2 = (k + 1) % n
                mid = (ring_i[k] + ring_i[k2]) / 2
                hint = np.array([0, cy - mid[1], cz - mid[2]])
                q = [ring_i[k], ring_d[k], ring_d[k2], ring_i[k2]]
                fn = normalize(np.cross(q[1] - q[0], q[3] - q[0]))
                if np.dot(fn, hint) < 0:
                    fn = -fn
                ids = [m.vertex(p, fuv(p[1], p[2]), fn) for p in q]
                m._quad(grupo, ids)
            m.fan(grupo, ring_d, np.array([xin, cy, cz]), [fuv(p[1], p[2]) for p in ring_d], fuv(cy, cz), (sign, 0, 0))


def borda(m, grupo, x0, x1, contorno, regiao):
    """Só a borda (parede) de uma peça oval, normal radial."""
    n = len(contorno)
    cy = sum(p[0] for p in contorno) / n
    cz = sum(p[1] for p in contorno) / n
    a0 = anel_x(x0, contorno)
    a1 = anel_x(x1, contorno)
    P = [a0 + [a0[0]], a1 + [a1[0]]]
    UV = [[uv(regiao, k / n, 0) for k in range(n + 1)], [uv(regiao, k / n, 1) for k in range(n + 1)]]
    m.grid(grupo, P, UV, outward=lambda i, p: np.array([p[0], cy, cz]))


def tampa(m, grupo, x, contorno, regiao, ext, sign):
    n = len(contorno)
    cy = sum(p[0] for p in contorno) / n
    cz = sum(p[1] for p in contorno) / n
    ring = anel_x(x, contorno)
    m.fan(grupo, ring, np.array([x, cy, cz]),
          [uv(regiao, 0.5 + (p[1] - cy) / (2 * ext), 0.5 + (p[2] - cz) / (2 * ext)) for p in ring],
          uv(regiao, 0.5, 0.5), (sign, 0, 0))


def coroa(m, grupo, x, de, para, regiao, sign):
    """Coroa plana entre dois contornos (mesmo número de pontos) em x."""
    n = len(de)
    m.strip(grupo, anel_x(x, de), anel_x(x, para), [uv(regiao, k / n, 0.0) for k in range(n)],
            [uv(regiao, k / n, 1.0) for k in range(n)], np.array([sign, 0, 0]))


def cabo_centro(x):
    return 2.4 + (x + 31.4) / 73.1 * 0.8


def cabo_raios(d, x):
    # leve cintura no meio, como as tsuka de verdade
    f = (x + 26.4) / 62.4
    k = 1 - 0.075 * math.sin(math.pi * min(1, max(0, f)))
    return d.cabo_ry * k, d.cabo_rz * k


def ito(d, x, ang):
    """Quanto o cordão do cabo cobre o ponto (0 no losango de pele, 1 no meio de uma fita)."""
    u = ang / (2 * math.pi)
    w = (x + 26.4) / d.ito_passo
    f1 = (w + 2 * u) % 1.0
    f2 = (w - 2 * u) % 1.0
    c1 = max(0.0, 1 - abs(f1 - 0.5) / 0.34)
    c2 = max(0.0, 1 - abs(f2 - 0.5) / 0.34)
    return min(1.0, max(c1, c2) * 1.6)


def tsuka(m, d, grupo):
    N = 24 if d.lod >= 1 else 12
    x_a, x_b = -26.4, 36.0
    secs = max(8, int(46 * d.lod))
    aneis = []
    for i in range(secs + 1):
        x = x_a + (x_b - x_a) * i / secs
        ry, rz = cabo_raios(d, x)
        cy = cabo_centro(x)
        ring = []
        for j in range(N):
            ang = 2 * math.pi * j / N
            c, s = math.cos(ang), math.sin(ang)
            e = 2.2
            yy = ry * math.copysign(abs(c) ** (2 / e), c)
            zz = rz * math.copysign(abs(s) ** (2 / e), s)
            bump = 1 + 0.07 * ito(d, x, ang)
            ring.append(np.array([x, cy + yy * bump, zz * bump]))
        aneis.append(ring)
    tubo_aneis(m, grupo, aneis, lambda i, f: uv("tsuka", i / secs, f), lambda i: np.array([aneis[i][0][0], cabo_centro(aneis[i][0][0]), 0]))
    return aneis


def contorno_cabo(d, x, extra=0.0, n=24):
    ry, rz = cabo_raios(d, x)
    return superellipse(n, ry + extra, rz + extra, 2.2, cabo_centro(x), 0.0)


def fuchi_kashira(m, d, grupo):
    # fuchi: colar de metal entre a seppa e o cordão
    xa, xb = -31.4, -26.4
    ca = contorno_cabo(d, xa, 0.6)
    cb = contorno_cabo(d, xb, 0.55)
    tubo_aneis(m, grupo, [anel_x(xa, ca), anel_x(xb, cb)], lambda i, f: uv("fuchi", f, i), lambda i: np.array([[xa, xb][i], cabo_centro([xa, xb][i]), 0]))
    coroa(m, grupo, xb, cb, contorno_cabo(d, xb, 0.0), "fuchi", 1.0)
    tampa(m, grupo, xa, ca, "fuchi", 8, -1.0)
    # kashira: o pomo, arredondado
    xs = [36.0, 38.6, 40.6, 41.4, 41.7]
    extras = [0.55, 0.62, 0.35, -1.0, -2.4]
    aneis = [anel_x(x, contorno_cabo(d, 36.0, e)) for x, e in zip(xs, extras)]
    tubo_aneis(m, grupo, aneis, lambda i, f: uv("kashira", f, i / (len(xs) - 1)), lambda i: np.array([xs[i] - 2, cabo_centro(36.0), 0]))
    coroa(m, grupo, 36.0, contorno_cabo(d, 36.0, 0.0), contorno_cabo(d, 36.0, 0.55), "kashira", -1.0)
    tampa(m, grupo, 41.7, contorno_cabo(d, 36.0, -2.4), "kashira", 8, 1.0)


def menuki(m, grupo, x, lado, cy):
    """Enfeite em meia-gota no lado plano do cabo (lado +1 ou -1 em z)."""
    rx, ry, h = 4.2, 1.8, 1.25
    N, R = 14, 5
    base_z = lado * 3.75
    aneis = []
    for r in range(R + 1):
        k = math.cos(r / R * math.pi / 2)
        zz = base_z + lado * h * math.sin(r / R * math.pi / 2)
        aneis.append([np.array([x + rx * k * math.cos(2 * math.pi * j / N), cy + ry * k * math.sin(2 * math.pi * j / N), zz]) for j in range(N)])
    aneis[-1] = [np.array([x, cy, base_z + lado * h])] * N
    P = [a + [a[0]] for a in aneis]
    UV = [[uv("menuki", 0.5 + 0.5 * (1 - r / R) * math.cos(2 * math.pi * j / N), 0.5 + 0.5 * (1 - r / R) * math.sin(2 * math.pi * j / N)) for j in range(N + 1)] for r in range(R + 1)]
    m.grid(grupo, P, UV, outward=lambda i, p: np.array([x, cy, base_z - lado * 2]))


def saya(m, d, grupo, regiao="saya", so_corpo=False):
    L = Lamina(d)
    N = 24 if d.lod >= 1 else 12

    def centro(x):
        s = L.s_de_x(x)
        if s > 1:
            # depois da ponta, continua reto
            c1 = L.centro(1.0)
            c0 = L.centro(0.97)
            t = normalize(c1 - c0)
            return c1 + t * (x - c1[0]) / t[0]
        return L.centro(min(1.0, s))

    def raios(x):
        s = min(1.0, L.s_de_x(x))
        _, _, w, _ = L.quadro(min(s, d.yokote))
        k = (x + 35.4) / (-296 + 35.4)
        return w / 2 + d.saya_parede - 0.25 * k, d.saya_rz - 0.5 * k

    def contorno(x, extra=0.0):
        c = centro(x)
        ry, rz = raios(x)
        return superellipse(N, ry + extra, rz + extra, 2.3, c[1], 0.0)

    xs = list(np.linspace(-41.4, -287.0, max(10, int(44 * d.lod))))
    aneis = [anel_x(x, contorno(x)) for x in xs]
    tubo_aneis(m, grupo, aneis, lambda i, f: uv(regiao, (xs[i] + 35.4) / (-296 + 35.4), f), lambda i: centro(xs[i]))
    if so_corpo:
        return centro, raios, None
    # koiguchi: boca, colar de chifre
    ka = contorno(-35.4, 0.45)
    kb = contorno(-41.4, 0.45)
    tubo_aneis(m, grupo, [anel_x(-35.4, ka), anel_x(-41.4, kb)], lambda i, f: uv("koiguchi", f, i), lambda i: centro([-35.4, -41.4][i]))
    coroa(m, grupo, -41.4, kb, contorno(-41.4), "koiguchi", -1.0)
    c0 = centro(-35.4)
    fenda = superellipse(N, raios(-35.4)[0] - d.saya_parede + 0.6, 1.6, 2.6, c0[1], 0.0)
    coroa(m, grupo, -35.4, ka, fenda, "koiguchi", 1.0)
    ring = anel_x(-35.4, fenda)
    m.fan(grupo, ring, np.array([-35.4, c0[1], 0]), [uv("boca", 0.5 + 0.45 * math.cos(2 * math.pi * k / N), 0.5 + 0.45 * math.sin(2 * math.pi * k / N)) for k in range(N)], uv("boca", 0.5, 0.5), (1, 0, 0))
    # kojiri: ponteira arredondada
    xk = [-287.0, -292.0, -294.8, -295.8, -296.2]
    ek = [0.4, 0.4, -0.2, -1.2, -2.2]
    ak = [anel_x(x, contorno(-287.0, e)) for x, e in zip(xk, ek)]
    for a, x in zip(ak, xk):
        c = centro(x)
        for p in a:
            p[1] += c[1] - centro(-287.0)[1]
    tubo_aneis(m, grupo, ak, lambda i, f: uv("kojiri", f, i / (len(xk) - 1)), lambda i: centro(xk[i] + 3))
    coroa(m, grupo, -287.0, contorno(-287.0), contorno(-287.0, 0.4), "kojiri", 1.0)
    tampa(m, grupo, -296.2, [(p[1], p[2]) for p in ak[-1]], "kojiri", 8, -1.0)
    # kurikata: o botão por onde passa o cordão, no lado +z, perto do fio
    xc = -63.0
    c = centro(xc)
    ry, rz = raios(xc)
    yk = c[1] + 0.35 * ry
    base = [(xc + 3.4 * math.cos(2 * math.pi * k / 16), yk + 1.9 * math.sin(2 * math.pi * k / 16)) for k in range(16)]
    z0, z1 = rz * 0.82, rz + 2.4
    a0 = [np.array([x, y, z0]) for x, y in base]
    a1 = [np.array([xc + (x - xc) * 0.85, yk + (y - yk) * 0.85, z1]) for x, y in base]
    P = [a0 + [a0[0]], a1 + [a1[0]]]
    UV = [[uv("kurikata", k / 16, 0.0) for k in range(17)], [uv("kurikata", k / 16, 0.5) for k in range(17)]]
    m.grid(grupo, P, UV, outward=lambda i, p: np.array([xc, yk, p[2]]))
    m.fan(grupo, a1, np.array([xc, yk, z1]), [uv("kurikata", 0.5 + 0.4 * math.cos(2 * math.pi * k / 16), 0.75 + 0.2 * math.sin(2 * math.pi * k / 16)) for k in range(16)], uv("kurikata", 0.5, 0.75), (0, 0, 1))
    return centro, raios, (xc, yk, z1)


def tubo(m, grupo, caminho, raio, regiao, lados=6, v0=0.0, v1=1.0):
    """Cordão: tubo de `lados` faces seguindo `caminho` (pontos 3D)."""
    caminho = [np.asarray(p, dtype=float) for p in caminho]
    n = len(caminho)
    aneis = []
    prev_side = None
    for i, p in enumerate(caminho):
        t = normalize(caminho[min(n - 1, i + 1)] - caminho[max(0, i - 1)])
        ref = np.array([0, 0, 1.0]) if abs(t[2]) < 0.9 else np.array([0, 1.0, 0])
        side = normalize(np.cross(t, ref)) if prev_side is None else normalize(prev_side - np.dot(prev_side, t) * t)
        prev_side = side
        up = np.cross(t, side)
        aneis.append([p + raio * (math.cos(2 * math.pi * k / lados) * side + math.sin(2 * math.pi * k / lados) * up) for k in range(lados)])
    comp = [0.0]
    for a, b in zip(caminho, caminho[1:]):
        comp.append(comp[-1] + np.linalg.norm(b - a))
    tot = comp[-1] or 1
    P = [a + [a[0]] for a in aneis]
    UV = [[uv(regiao, v0 + (v1 - v0) * comp[i] / tot, k / lados) for k in range(lados + 1)] for i in range(n)]
    m.grid(grupo, P, UV, outward=lambda i, p: caminho[i])


def sageo(m, d, grupo, centro, raios, nó):
    # duas voltas em torno da bainha, perto do kurikata
    for x in (-68.5, -73.5):
        c = centro(x)
        ry, rz = raios(x)
        cont = superellipse(28, ry + 0.75, rz + 0.75, 2.3, c[1], 0.0)
        path = [np.array([x, y, z]) for (y, z) in cont]
        path.append(path[0])
        tubo(m, grupo, path, 0.75, "sageo", 6, 0.0, 0.3)
    # laço no kurikata e duas pontas caindo para o lado da ponta
    xc, yk, z1 = nó
    for sgn in (1, -1):
        loop = []
        for k in range(13):
            a = math.pi * k / 12
            loop.append(np.array([xc - 2 + sgn * 5.5 * math.sin(a), yk + 3.2 * math.sin(a) * 0.4 + 1.5, z1 + 0.6 + 2.2 * math.sin(a)]))
        tubo(m, grupo, loop, 0.7, "sageo", 6, 0.3, 0.5)
    for k_off, length in ((1.2, 26.0), (-1.2, 20.0)):
        path = []
        for k in range(14):
            f = k / 13
            path.append(np.array([xc - 2 - f * length, yk - 1.0 - 7.0 * f * f + k_off * f, z1 + 0.4 - 1.8 * f]))
        tubo(m, grupo, path, 0.7, "sageo", 6, 0.5, 1.0)


def casca(m, d, grupo):
    """A casca do brilho de carga: envolve a lâmina (da habaki à ponta)."""
    L = Lamina(d)
    ss = [s for s in secoes(d) if L.x(s) <= -36.0]
    N = 14
    aneis = []
    for s in ss + [1.0]:
        b, n, w, t = L.quadro(min(s, 1.0))
        c = L.centro(min(s, 1.0))
        ext = 1.6 if s < 1.0 else 0.0
        nn = np.array([n[0], n[1], 0.0])
        ring = []
        for j in range(N):
            a = 2 * math.pi * j / N
            ca, sa = math.cos(a), math.sin(a)
            ring.append(c + (w / 2 + ext) * math.copysign(abs(ca) ** 0.8, ca) * nn + np.array([0, 0, (t + 1.4) * math.copysign(abs(sa) ** 0.8, sa)]))
        aneis.append(ring)
    # ponta além da lâmina
    tip = L.centro(1.0) + np.array([-2.5, -0.6, 0])
    aneis[-1] = [tip] * N
    tubo_aneis(m, grupo, aneis, lambda i, f: uv("brilho", i / (len(aneis) - 1), f), lambda i: L.centro(min(1.0, ss[min(i, len(ss) - 1)])))


# ---- a espada inteira -----------------------------------------------------------------------------------------------
def contorno_tsuba(d, cy, n):
    pts = superellipse(n, d.tsuba_ry, d.tsuba_rz, d.tsuba_e, cy, 0.0, start=math.pi / max(1, d.tsuba_lobos) if d.tsuba_lobos else 0.0)
    if not d.tsuba_lobos:
        return pts
    out = []
    for (y, z) in pts:
        a = math.atan2(z / d.tsuba_rz, (y - cy) / d.tsuba_ry)
        k = 1 - d.tsuba_lobo_fundo * (1 - abs(math.cos(d.tsuba_lobos * a / 2))) ** 3
        out.append((cy + (y - cy) * k, z * k))
    return out


def guarda_e_cabo(m, d, grupo):
    L = Lamina(d)
    habaki(m, d, grupo)
    c_base = L.centro(L.s_de_x(-34.0))
    seppa = superellipse(32, 7.2, 3.4, 2.4, c_base[1], 0.0)
    prato(m, grupo, -37.6, -37.0, seppa, "seppa", "seppa", 8.0)
    tsuba_c = contorno_tsuba(d, c_base[1], 48 if d.lod >= 1 else 20)
    prato(m, grupo, -37.0, -32.6, tsuba_c, "tsuba", "tsuba_aro", max(d.tsuba_ry, d.tsuba_rz) + 0.5, aro=(0.11, 0.35))
    seppa2 = superellipse(32, 7.0, 4.6, 2.4, cabo_centro(-31.4), 0.0)
    prato(m, grupo, -32.6, -32.0, seppa2, "seppa", "seppa", 8.0)
    tsuka(m, d, grupo)
    fuchi_kashira(m, d, grupo)
    if d.lod >= 1:
        menuki(m, grupo, 6.0, 1, cabo_centro(6.0))
        menuki(m, grupo, -4.5, -1, cabo_centro(-4.5))


def tsuba_luz(m, d, grupo):
    """As faces da guarda de novo, um fio à frente, com a região que brilha."""
    L = Lamina(d)
    c_base = L.centro(L.s_de_x(-34.0))
    cont = contorno_tsuba(d, c_base[1], 48)
    ext = max(d.tsuba_ry, d.tsuba_rz) + 0.5
    n = len(cont)
    cy = c_base[1]
    for x, sign in ((-37.0 - 0.02, -1.0), (-32.6 + 0.02, 1.0)):
        ring = anel_x(x, cont)
        m.fan(grupo, ring, np.array([x, cy, 0.0]),
              [uv("tsuba_luz", 0.5 + (p[1] - cy) / (2 * ext), 0.5 + p[2] / (2 * ext)) for p in ring],
              uv("tsuba_luz", 0.5, 0.5), (sign, 0, 0))


def montar(d, icones_de=None):
    m = Mesh()
    L = Lamina(d)
    # inteira
    lamina(m, d, "blade", secoes(d))
    guarda_e_cabo(m, d, "blade")
    # quebrada: corte irregular em x = -110 (como o SlashBlade)
    s_b = L.s_de_x(-110.0)
    rng = np.random.default_rng(7)
    A, _, _ = perfil(d)
    jag = list(0.018 * (rng.random(len(A)) - 0.5))
    jag[0] -= 0.02
    jag[-1] += 0.012
    s_col = [s_b + j for j in jag]
    sl = secoes(d, 0.0, s_b)
    lamina(m, d, "blade_damaged", sl, quebra=lambda i, j, s: s_col[j] if i == len(sl) - 1 else s)
    guarda_e_cabo(m, d, "blade_damaged")
    tampa_quebra(m, d, "blade_damaged", s_col, -1.0)
    sf = secoes(d, s_b, 1.0)
    lamina(m, d, "blade_fragment", sf, quebra=lambda i, j, s: s_col[j] if i == 0 else s)
    tampa_quebra(m, d, "blade_fragment", s_col, 1.0)
    luz = d.textura.get("luz", ())
    if "lamina" in luz:
        lamina(m, d, "blade_luminous", secoes(d), regiao="lamina_luz")
        lamina(m, d, "blade_damaged_luminous", sl, quebra=lambda i, j, s: s_col[j] if i == len(sl) - 1 else s,
               regiao="lamina_luz")
    if "tsuba" in luz:
        tsuba_luz(m, d, "blade_luminous")
        tsuba_luz(m, d, "blade_damaged_luminous")
    if "saya" in luz:
        saya(m, d, "sheath_luminous", regiao="saya_luz", so_corpo=True)
    for g in ("blade_luminous", "blade_damaged_luminous", "sheath_luminous"):
        m.inflate(g, 0.04)
    # bainha
    centro, raios, no = saya(m, d, "sheath")
    if d.sageo:
        sageo(m, d, "sheath", centro, raios, no)
    # brilho de carga
    casca(m, d, "effect")
    if icones_de is None and d.lod >= 1:
        # ícones: de uma versão leve da mesma katana
        import dataclasses
        leve = montar(dataclasses.replace(d, lod=0.4, sageo=False), icones_de=False)
        icones(m, leve)
    return m


def icones(m, fonte):
    ang = math.radians(-45)
    R = np.array([[math.cos(ang), -math.sin(ang), 0], [math.sin(ang), math.cos(ang), 0], [0, 0, 1]])
    k = 0.6
    meio = np.array([-124.6, 2.0, 0.0])
    diag = np.array([-1.0, 1.0, 0.0]) / math.sqrt(2)
    perp = np.array([1.0, 1.0, 0.0]) / math.sqrt(2)

    def tf(desloc, z):
        return lambda p: R @ ((p - meio) * k) + diag * 10 + perp * desloc + np.array([0, 0, z])

    rot = lambda n: R @ n
    m.copy_group("blade", "item_bladens", tf(0, 0), rot, fonte)
    m.copy_group("blade", "item_blade", tf(7, 4), rot, fonte)
    m.copy_group("sheath", "item_blade", tf(-9, -4), rot, fonte)
    m.copy_group("blade_damaged", "item_damaged", tf(4, 2), rot, fonte)
    m.copy_group("blade_fragment", "item_damaged", tf(-10, -2), rot, fonte)
    m.copy_group("blade_luminous", "item_bladens_luminous", tf(0, 0), rot, fonte)
    m.copy_group("blade_luminous", "item_blade_luminous", tf(7, 4), rot, fonte)
    m.copy_group("sheath_luminous", "item_blade_luminous", tf(-9, -4), rot, fonte)
