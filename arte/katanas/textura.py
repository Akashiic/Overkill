"""Pinta o atlas 2048 x 1024 de uma katana, região por região, com as mesmas coordenadas da geometria (katana.py).

Tudo procedural (ruídos e padrões em numpy), com a luz suave já pintada como nas texturas do SlashBlade: o fio claro,
o hamon com nie, o hada do aço, a seda do cabo sobre a pele de arraia, o ferro da guarda, o cobre da habaki, a laca
da bainha. As cores vêm de `Desenho.textura` (com os padrões da katana de aço).
"""

import math

import numpy as np
from PIL import Image

from katana import ATLAS, ATLAS_H, ATLAS_W, Desenho, ito, perfil_v

# ---- ruídos -------------------------------------------------------------------------------------------------------


def ruido(h, w, escala_y, escala_x, seed, oitavas=4, persist=0.5):
    """fBm suave (0..1): grades aleatórias ampliadas em bicúbico, somadas por oitavas."""
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, tot = 1.0, 0.0
    for o in range(oitavas):
        gh = max(2, int(h / escala_y * 2**o))
        gw = max(2, int(w / escala_x * 2**o))
        g = rng.random((gh, gw)).astype(np.float32)
        img = Image.fromarray(g, mode="F").resize((w, h), Image.BICUBIC)
        out += amp * np.asarray(img)
        tot += amp
        amp *= persist
    out /= tot
    lo, hi = np.percentile(out, 1), np.percentile(out, 99)
    return np.clip((out - lo) / (hi - lo + 1e-9), 0, 1)


def graos(h, w, densidade, seed):
    """Pontinhos brilhantes (nie, poeira): 0..1."""
    rng = np.random.default_rng(seed)
    g = (rng.random((h, w)) < densidade).astype(np.float32) * rng.random((h, w))
    return g


def suave(x, a, b):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def mistura(a, b, t):
    t = t[..., None] if np.ndim(t) == np.ndim(a) - 1 else t
    return a * (1 - t) + b * t


def cor(c):
    return np.array(c, dtype=np.float32) / 255.0


def grade(regiao):
    """Coordenadas fu (0..1 em x) e fv (0..1 em y) de cada pixel da região."""
    x, y, w, h = ATLAS[regiao]
    fu = (np.arange(w) + 0.5 - 0.5) / max(1, w - 1)
    fv = (np.arange(h) + 0.5 - 0.5) / max(1, h - 1)
    FU, FV = np.meshgrid(fu, fv)
    return FU, FV


# ---- paleta padrão (katana de aço) -----------------------------------------------------------------------------------
PADRAO = {
    "aco_ji": (120, 128, 142),          # aço do corpo da lâmina
    "aco_shinogi": (92, 98, 112),     # shinogi-ji, polido escuro
    "aco_mune": (122, 128, 140),
    "hamon": (228, 234, 242),        # a têmpera, branca e fosca
    "nioi": (238, 242, 248),         # a névoa da linha do hamon
    "fio": (232, 236, 242),
    "hamon_tipo": "notare",          # notare (ondas largas), gunome (meias-luas), suguha (reto)
    "hamon_altura": 0.30,            # fração da largura a partir do fio
    "ito": (26, 28, 38),             # seda do cabo
    "ito_brilho": (86, 92, 118),
    "same": (226, 220, 204),         # pele de arraia
    "ferro": (52, 50, 48),           # guarda
    "ferro_brilho": (120, 116, 110),
    "cobre": (176, 104, 52),         # habaki e seppa
    "cobre_brilho": (238, 176, 112),
    "metal_cabo": (34, 33, 36),      # fuchi e kashira (shakudo)
    "metal_cabo_brilho": (122, 118, 128),
    "laca": (16, 16, 20),            # bainha
    "laca_brilho": (96, 100, 116),
    "chifre": (30, 26, 24),          # koiguchi, kojiri, kurikata
    "chifre_brilho": (110, 100, 92),
    "ouro": (168, 116, 30),          # menuki
    "ouro_brilho": (255, 214, 104),
    "sageo": (36, 42, 70),
    "sageo_brilho": (104, 116, 168),
    "seed": 11,
}


class Pintor:
    def __init__(self, d: Desenho):
        self.d = d
        self.p = dict(PADRAO)
        self.p.update(d.textura)
        self.img = np.zeros((ATLAS_H, ATLAS_W, 4), dtype=np.float32)
        self.seed = self.p["seed"]

    def put(self, regiao, rgb, alpha=None):
        x, y, w, h = ATLAS[regiao]
        self.img[y:y + h, x:x + w, :3] = np.clip(rgb, 0, 1)
        self.img[y:y + h, x:x + w, 3] = 1.0 if alpha is None else alpha

    def c(self, nome):
        return cor(self.p[nome])

    # ---- lâmina ----------------------------------------------------------------------------------------------------
    def lamina(self):
        d, p = self.d, self.p
        FU, FV = grade("lamina")
        h, w = FU.shape
        V = perfil_v(d)
        tsurugi = d.perfil == "tsurugi"
        v_shin = V[3] if tsurugi else V[5]
        v_mune = V[-2]
        s = FU
        # posição através da largura: 0 no fio, 1 no dorso (para o hamon), pelo perfil
        across = FV / v_shin * (0.66 if not tsurugi else 0.5)
        # linha do hamon
        alt = p["hamon_altura"]
        tipo = p["hamon_tipo"]
        rng_phase = (self.seed % 7) * 0.37
        if tipo == "suguha":
            onda = 0.012 * np.sin(s * 60 + rng_phase)
        elif tipo == "gunome":
            onda = 0.05 * np.abs(np.sin(s * 34 + rng_phase)) ** 0.7 + 0.012 * np.sin(s * 140)
        elif tipo == "midare":
            onda = 0.06 * (ruido(1, w, 1, 24, self.seed + 3, 3)[0][None, :] - 0.5) * 2 + 0.03 * np.abs(np.sin(s * 47))
        else:  # notare: ondas largas e calmas
            onda = 0.04 * np.sin(s * 19 + rng_phase) + 0.015 * np.sin(s * 53 + 1.3)
        linha = alt + onda
        # perto da base o hamon começa baixo (hamachi) e no kissaki faz o boshi, que volta
        linha = linha * (0.55 + 0.45 * suave(s, 0.0, 0.08))
        kiss = suave(s, d.yokote - 0.005, d.yokote + 0.01)
        linha = mistura(linha, 0.30 + 0.0 * s, kiss * 0.5)
        dentro = 1 - suave(across, linha - 0.02, linha + 0.02)
        nioi = np.exp(-((across - linha) / 0.03) ** 2)
        # ashi: riscos finos da têmpera descendo do hamon para o fio
        rng = np.random.default_rng(self.seed + 7)
        ashi = np.zeros_like(s)
        for s_k in np.sort(rng.random(int(70 * d.yokote))) * d.yokote:
            larg = 0.0006 + 0.0008 * rng.random()
            prof = 0.35 + 0.4 * rng.random()
            ashi += np.exp(-((s - s_k) / larg) ** 2) * suave(across, linha * (1 - prof), linha * (1 - prof) + 0.03) * (across < linha)
        ashi = np.clip(ashi, 0, 1)
        # aço: hada itame (veio de madeira) esticado ao longo da lâmina
        hada = ruido(h, w, 6, 90, self.seed + 1, 5, 0.55)
        hada2 = ruido(h, w, 3, 30, self.seed + 2, 3)
        nie = graos(h, w, 0.035, self.seed + 4)
        ji = self.c("aco_ji")[None, None, :] * (0.86 + 0.22 * hada[..., None] + 0.06 * hada2[..., None])
        # reflexo do ambiente: faixa clara perto do fio e escurecendo para o shinogi
        ji *= (1.08 - 0.20 * suave(across, 0.2, 0.66))[..., None]
        hamon = self.c("hamon")[None, None, :] * (0.90 + 0.10 * hada2[..., None]) + 0.18 * nie[..., None] * (1 - dentro[..., None] * 0.3)
        # o hamon é mais claro perto da linha (nioiguchi) e um pouco mais cinza perto do fio
        hamon *= (0.9 + 0.12 * suave(across, 0.0, linha))[..., None]
        rgb = mistura(ji, hamon, dentro)
        rgb = rgb + self.c("nioi")[None, None, :] * 0.22 * nioi[..., None] + 0.07 * ashi[..., None]
        # o fio: faixa estreita e brilhante
        fio = 1 - suave(FV, 0.0, 0.035)
        rgb = mistura(rgb, self.c("fio")[None, None, :] * np.ones_like(rgb), fio * 0.85)
        # shinogi-ji: polido escuro, com riscos ao longo
        riscos = ruido(h, w, 1.5, 200, self.seed + 5, 2)
        shin = self.c("aco_shinogi")[None, None, :] * (0.85 + 0.25 * riscos[..., None])
        if not tsurugi:
            m_shin = suave(FV, v_shin - 0.004, v_shin + 0.004)
            rgb = mistura(rgb, shin, m_shin)
            # a aresta do shinogi: linha clara fina
            rgb += 0.32 * np.exp(-((FV - v_shin) / 0.006) ** 2)[..., None]
            # mune
            m_mune = suave(FV, v_mune - 0.004, v_mune + 0.004)
            mune = self.c("aco_mune")[None, None, :] * (0.9 + 0.15 * riscos[..., None]) * (1.15 - 0.3 * suave(FV, v_mune, 1.0))[..., None]
            rgb = mistura(rgb, mune, m_mune)
            rgb += 0.25 * np.exp(-((FV - v_mune) / 0.005) ** 2)[..., None]
        else:
            # segundo fio espelhado
            fio2 = suave(FV, 0.965, 1.0)
            rgb = mistura(rgb, self.c("fio")[None, None, :] * np.ones_like(rgb), fio2 * 0.85)
            rgb += 0.30 * np.exp(-((FV - v_shin) / 0.008) ** 2)[..., None]
        # yokote: a linha que separa a ponta
        if not tsurugi:
            yok = np.exp(-((s - d.yokote) / 0.0016) ** 2) * (FV < v_shin + 0.02)
            rgb += 0.45 * yok[..., None]
            # na ponta o polimento é mais claro (narume)
            rgb = mistura(rgb, rgb * 1.12 + 0.03, suave(s, d.yokote, d.yokote + 0.01) * (FV < v_shin) * 0.6)
        # perto da habaki, o aço fica escondido: ligeiramente mais escuro
        rgb *= (0.92 + 0.08 * suave(s, 0.0, 0.05))[..., None]
        extra = self.p.get("lamina_extra")
        if extra:
            rgb = extra(self, rgb, FU, FV, dentro, across)
        self.put("lamina", rgb)
        self.mascaras = {"dentro": dentro, "across": across, "linha": linha, "nioi": nioi, "v_shin": v_shin}

    # ---- cabo ------------------------------------------------------------------------------------------------------
    def tsuka(self):
        d = self.d
        FU, FV = grade("tsuka")
        h, w = FU.shape
        x = -26.4 + FU * 62.4
        ang = 2 * np.pi * FV
        u = ang / (2 * np.pi)
        wv = (x + 26.4) / d.ito_passo
        f1 = (wv + 2 * u) % 1.0
        f2 = (wv - 2 * u) % 1.0
        c1 = np.clip(1 - np.abs(f1 - 0.5) / 0.34, 0, 1)
        c2 = np.clip(1 - np.abs(f2 - 0.5) / 0.34, 0, 1)
        # qual fita fica por cima alterna a cada cruzamento (a trama)
        cruz = (np.floor(wv * 2) % 2) == 0
        top1 = np.where(cruz, c1 >= c2 * 0.999, c1 > c2)
        cov = np.clip(np.maximum(c1, c2) * 1.6, 0, 1)
        banda = np.where(top1, c1, c2)
        # sombreamento da fita: arredondada, com fios diagonais
        fase = np.where(top1, f1, f2)
        fios = 0.5 + 0.5 * np.sin((fase * 2 + (np.where(top1, wv - 2 * u, wv + 2 * u))) * 2 * np.pi * 9)
        luz = np.sin(np.clip(banda, 0, 1) * np.pi / 2)
        seda = mistura(self.c("ito")[None, None, :] * np.ones((h, w, 3)), self.c("ito_brilho")[None, None, :] * np.ones((h, w, 3)), luz * 0.55 + 0.12 * fios)
        # pele de arraia nos losangos: bolinhas
        rng = np.random.default_rng(self.seed + 9)
        same = self.c("same")[None, None, :] * (0.82 + 0.18 * ruido(h, w, 3, 3, self.seed + 10, 2))[..., None]
        bol = graos(h, w, 0.06, self.seed + 11)
        same = same * (1 - 0.18 * bol[..., None])
        # sombra da fita sobre a pele
        sombra = suave(cov, 0.0, 0.5)
        same = same * (1 - 0.45 * sombra)[..., None]
        rgb = mistura(same, seda, suave(cov, 0.48, 0.62))
        # escurece embaixo das bordas (z = 0, as quinas do cabo) e clareia em cima
        rgb *= (0.92 + 0.12 * np.abs(np.sin(ang)))[..., None]
        # mekugi: o pino de bambu, nos dois lados planos
        for lado_ang in (np.pi / 2, 3 * np.pi / 2):
            dx = (x - (-12.5)) / 1.3
            da = (ang - lado_ang) / 0.21
            r = np.sqrt(dx**2 + da**2)
            pino = 1 - suave(r, 0.8, 1.0)
            rgb = mistura(rgb, np.ones_like(rgb) * cor((150, 120, 70)) * (0.8 + 0.3 * (1 - r))[..., None], pino)
        self.put("tsuka", rgb)

    # ---- guarda ----------------------------------------------------------------------------------------------------
    def tsuba(self):
        d = self.d
        FU, FV = grade("tsuba")
        h, w = FU.shape
        ext = max(d.tsuba_ry, d.tsuba_rz) + 0.5
        y = (FU - 0.5) * 2 * ext
        z = (FV - 0.5) * 2 * ext
        e = d.tsuba_e
        r = (np.abs(y / d.tsuba_ry) ** e + np.abs(z / d.tsuba_rz) ** e) ** (1 / e)
        martelo = ruido(h, w, 10, 10, self.seed + 20, 4)
        base = self.c("ferro")[None, None, :] * (0.78 + 0.4 * martelo[..., None])
        # aro claro
        aro = suave(r, 0.84, 0.9) * (1 - suave(r, 0.98, 1.02))
        rgb = mistura(base, self.c("ferro_brilho")[None, None, :] * np.ones_like(base), aro * 0.7)
        # anéis concêntricos sutis gravados
        rgb *= (1 - 0.08 * (np.sin(r * 60) > 0.6) * (r < 0.8))[..., None]
        # furos laterais (kozuka e kogai), pintados como fundo escuro com borda
        for cy_, cz_, ry_, rz_ in ((0.02, 0.48, 0.12, 0.2), (0.02, -0.48, 0.12, 0.2)):
            rr = np.sqrt(((y / d.tsuba_ry - cy_) / ry_) ** 2 + ((z / d.tsuba_rz - cz_) / rz_) ** 2)
            buraco = 1 - suave(rr, 0.85, 1.0)
            borda_b = np.exp(-((rr - 1.0) / 0.12) ** 2)
            rgb = mistura(rgb, np.ones_like(rgb) * 0.05, buraco)
            rgb += 0.15 * borda_b[..., None]
        # sombra perto do centro (onde encostam a seppa e a lâmina)
        rgb *= (0.75 + 0.25 * suave(r, 0.25, 0.5))[..., None]
        extra = self.p.get("tsuba_extra")
        if extra:
            rgb = extra(self, rgb, y, z, r)
        self.put("tsuba", rgb)
        FU, FV = grade("tsuba_aro")
        h, w = FU.shape
        m = ruido(h, w, 8, 40, self.seed + 21, 3)
        rgb = self.c("ferro")[None, None, :] * (0.9 + 0.3 * m[..., None]) + 0.18 * np.exp(-((FV - 0.5) / 0.3) ** 2)[..., None]
        self.put("tsuba_aro", rgb)

    # ---- metais pequenos -------------------------------------------------------------------------------------------
    def metal(self, regiao, base, brilho, riscos_dir=None, seed=0):
        FU, FV = grade(regiao)
        h, w = FU.shape
        n = ruido(h, w, 6, 6, self.seed + 30 + seed, 3)
        rgb = self.c(base)[None, None, :] * (0.82 + 0.3 * n[..., None])
        # brilho: faixa clara no meio da volta (a luz de cima)
        luz = np.exp(-((FV - 0.3) / 0.12) ** 2) + 0.6 * np.exp(-((FV - 0.8) / 0.08) ** 2)
        rgb = mistura(rgb, self.c(brilho)[None, None, :] * np.ones_like(rgb), np.clip(luz * 0.55, 0, 1))
        if riscos_dir is not None:
            # neko-gaki: riscos diagonais da lima
            t = FU * w * math.cos(riscos_dir) + FV * h * math.sin(riscos_dir)
            rgb *= (0.88 + 0.12 * (np.sin(t * 0.9) > 0.3))[..., None]
        self.put(regiao, rgb)

    def bainha(self):
        d = self.d
        FU, FV = grade("saya")
        h, w = FU.shape
        ang = 2 * np.pi * FV
        n = ruido(h, w, 4, 60, self.seed + 40, 4)
        base = self.c("laca")[None, None, :] * (0.85 + 0.3 * n[..., None])
        # laca brilhante: reflexo comprido nos dois lados planos e nas quinas
        luz = (np.exp(-((np.sin(ang) - 0.75) / 0.12) ** 2) + np.exp(-((np.sin(ang) + 0.75) / 0.12) ** 2) * 0.55
               + 0.5 * np.exp(-((np.cos(ang) - 1) / 0.05) ** 2) + 0.35 * np.exp(-((np.cos(ang) + 1) / 0.05) ** 2))
        luz *= 0.75 + 0.25 * ruido(h, w, 2, 300, self.seed + 41, 2)
        rgb = mistura(base, self.c("laca_brilho")[None, None, :] * np.ones_like(base), np.clip(luz * 0.55, 0, 1))
        extra = self.p.get("saya_extra")
        if extra:
            rgb = extra(self, rgb, FU, FV, ang)
        self.put("saya", rgb)

    def cordao(self):
        FU, FV = grade("sageo")
        h, w = FU.shape
        # trança em espinha de peixe
        t = (FU * 260 + np.abs(FV - 0.5) * 6) % 1.0
        trama = 0.5 + 0.5 * np.sin(t * 2 * np.pi)
        luz = np.exp(-((FV - 0.35) / 0.25) ** 2)
        rgb = mistura(self.c("sageo")[None, None, :] * np.ones((h, w, 3)), self.c("sageo_brilho")[None, None, :] * np.ones((h, w, 3)), np.clip(0.25 * trama + 0.45 * luz, 0, 1))
        self.put("sageo", rgb)

    def pequenos(self):
        self.metal("habaki", "cobre", "cobre_brilho", riscos_dir=math.radians(60), seed=1)
        self.metal("seppa", "cobre", "cobre_brilho", seed=2)
        self.metal("fuchi", "metal_cabo", "metal_cabo_brilho", seed=3)
        self.metal("kashira", "metal_cabo", "metal_cabo_brilho", seed=4)
        self.metal("koiguchi", "chifre", "chifre_brilho", seed=5)
        self.metal("kojiri", "chifre", "chifre_brilho", seed=6)
        self.metal("kurikata", "chifre", "chifre_brilho", seed=7)
        # menuki: ouro em relevo
        FU, FV = grade("menuki")
        h, w = FU.shape
        r = np.sqrt((FU - 0.5) ** 2 + (FV - 0.5) ** 2) * 2
        relevo = ruido(h, w, 4, 4, self.seed + 50, 3)
        rgb = mistura(self.c("ouro")[None, None, :] * np.ones((h, w, 3)), self.c("ouro_brilho")[None, None, :] * np.ones((h, w, 3)), np.clip(relevo * (1 - r) * 1.2, 0, 1))
        self.put("menuki", rgb)
        # a boca da bainha: escura
        FU, FV = grade("boca")
        self.put("boca", np.ones(FU.shape + (3,)) * 0.03)
        # quebra: aço cristalino
        FU, FV = grade("quebra")
        h, w = FU.shape
        q = ruido(h, w, 3, 3, self.seed + 60, 2)
        self.put("quebra", np.ones((h, w, 3)) * (0.45 + 0.35 * q)[..., None] * cor((200, 206, 214)))
        # casca do brilho: degradê claro (o motor desenha a carga com outra textura)
        FU, FV = grade("brilho")
        self.put("brilho", np.ones(FU.shape + (3,)) * (0.7 + 0.3 * np.sin(FV * np.pi))[..., None])

    def luzes(self):
        """As regiões do passe aditivo: preto (não soma nada), ou o que cada katana pinta."""
        for regiao in ("lamina_luz", "saya_luz", "tsuba_luz"):
            FU, FV = grade(regiao)
            fn = self.p.get(regiao)
            rgb = fn(self, FU, FV) if fn else np.zeros(FU.shape + (3,))
            self.put(regiao, rgb)

    def pintar(self):
        self.lamina()
        self.tsuka()
        self.tsuba()
        self.pequenos()
        self.bainha()
        self.cordao()
        self.luzes()
        # o que sobra do atlas: transparente
        return self.img

    def salvar(self, caminho):
        rgba = (np.clip(self.img, 0, 1) * 255 + 0.5).astype(np.uint8)
        Image.fromarray(rgba, "RGBA").save(caminho, optimize=True)
