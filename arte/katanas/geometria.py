"""Geometria das katanas do Overkill, no formato que o motor de lâminas do Künftig lê (o mesmo do SlashBlade).

Convenções (medidas no modelo do SlashBlade para as animações caírem iguais):
  * unidade: 1/128 de bloco;
  * a ponta da lâmina aponta para -X (ponta em x = -291), o pomo fica em x = +41,7;
  * o fio (ha) fica em +Y, o dorso (mune) em -Y, a espessura em Z;
  * grupos: blade (espada inteira), blade_damaged + blade_fragment (quebrada), sheath (bainha), effect (casca do
    brilho de carga), item_blade, item_bladens, item_damaged (ícone no inventário) e *_luminous (partes que brilham).

Tudo aqui é nosso: nenhuma geometria do SlashBlade é copiada, só as medidas de encaixe.
"""

import math

import numpy as np


def normalize(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True)
    return v / np.where(n == 0, 1, n)


class Mesh:
    """Triângulos agrupados, com posição, UV e normal por vértice, escritos em OBJ."""

    def __init__(self):
        self.v = []
        self.vt = []
        self.vn = []
        self.groups = {}
        self.order = []

    def group(self, name):
        if name not in self.groups:
            self.groups[name] = []
            self.order.append(name)
        return self.groups[name]

    def vertex(self, p, uv, n):
        self.v.append(tuple(float(c) for c in p))
        self.vt.append((float(uv[0]), float(uv[1])))
        self.vn.append(tuple(float(c) for c in n))
        return len(self.v)  # OBJ conta a partir de 1

    def tri(self, group, a, b, c):
        self.group(group).append((a, b, c))

    def grid(self, group, P, UV, outward=None, closed=False, flip=False):
        """Uma superfície em grade: P[i][j] (i ao longo, j em volta), UV[i][j] em pixels do atlas (já em 0..1).

        Normais suaves dentro da grade. A ordem dos triângulos é escolhida para a normal apontar para fora:
        `outward(p)` dá um ponto de dentro (o centro da seção); sem ele vale `flip`.
        """
        P = np.asarray(P, dtype=float)
        UV = np.asarray(UV, dtype=float)
        ni, nj = P.shape[0], P.shape[1]
        du = np.zeros_like(P)
        dv = np.zeros_like(P)
        du[1:-1] = P[2:] - P[:-2]
        du[0] = P[1] - P[0]
        du[-1] = P[-1] - P[-2]
        if closed:
            dv = np.roll(P, -1, axis=1) - np.roll(P, 1, axis=1)
        else:
            dv[:, 1:-1] = P[:, 2:] - P[:, :-2]
            dv[:, 0] = P[:, 1] - P[:, 0]
            dv[:, -1] = P[:, -1] - P[:, -2]
        N = normalize(np.cross(du, dv))
        # sentido: para fora do centro da seção (cada seção i tem o seu centro)
        if outward is not None:
            votes = 0.0
            for i in range(ni):
                for j in range(nj):
                    votes += np.dot(N[i, j], P[i, j] - outward(i, P[i, j]))
            if votes < 0:
                N = -N
        elif flip:
            N = -N
        idx = [[self.vertex(P[i, j], UV[i, j], N[i, j]) for j in range(nj)] for i in range(ni)]
        jmax = nj if closed else nj - 1
        for i in range(ni - 1):
            for j in range(jmax):
                j2 = (j + 1) % nj
                a, b, c, d = idx[i][j], idx[i + 1][j], idx[i + 1][j2], idx[i][j2]
                self._quad(group, (a, b, c, d))
        return idx

    def _quad(self, group, q, sign=1):
        a, b, c, d = q
        for t in ((a, b, c), (a, c, d)):
            pa, pb, pc = (np.array(self.v[k - 1]) for k in t)
            n = np.cross(pb - pa, pc - pa)
            if np.linalg.norm(n) < 1e-12:
                continue  # degenerado (a ponta)
            ref = sum(np.array(self.vn[k - 1]) for k in t)
            if np.dot(n, ref) < 0:
                t = (t[0], t[2], t[1])
            self.tri(group, *t)

    def fan(self, group, ring, center, uv_ring, uv_center, normal):
        """Tampa plana: leque de `ring` (pontos) até `center`, com normal fixa."""
        n = normalize(np.asarray(normal, dtype=float))
        c = self.vertex(center, uv_center, n)
        ids = [self.vertex(p, uv, n) for p, uv in zip(ring, uv_ring)]
        for k in range(len(ids)):
            t = (c, ids[k], ids[(k + 1) % len(ids)])
            pa, pb, pc = (np.array(self.v[x - 1]) for x in t)
            if np.dot(np.cross(pb - pa, pc - pa), n) < 0:
                t = (t[0], t[2], t[1])
            self.tri(group, *t)

    def strip(self, group, ring_a, ring_b, uv_a, uv_b, normal_hint):
        """Faixa entre dois anéis (mesmo número de pontos, fechados), normal por face voltada para `normal_hint`."""
        n = len(ring_a)
        for k in range(n):
            k2 = (k + 1) % n
            quad = [ring_a[k], ring_b[k], ring_b[k2], ring_a[k2]]
            uvs = [uv_a[k], uv_b[k], uv_b[k2], uv_a[k2]]
            fn = normalize(np.cross(np.subtract(quad[1], quad[0]), np.subtract(quad[3], quad[0])))
            if np.dot(fn, normal_hint) < 0:
                fn = -fn
            ids = [self.vertex(p, uv, fn) for p, uv in zip(quad, uvs)]
            self._quad(group, ids, 1)

    def copy_group(self, src, dst, transform=None, normal_transform=None, fonte=None):
        """Copia os triângulos de `src` (desta malha ou de `fonte`) para `dst`, opcionalmente transformados."""
        fonte = fonte or self
        remap = {}
        for t in fonte.groups.get(src, []):
            new = []
            for k in t:
                if k not in remap:
                    p = np.array(fonte.v[k - 1])
                    n = np.array(fonte.vn[k - 1])
                    if transform is not None:
                        p = transform(p)
                        n = normalize(normal_transform(n))
                    remap[k] = self.vertex(p, fonte.vt[k - 1], n)
                new.append(remap[k])
            self.tri(dst, *new)

    def inflate(self, group, d):
        """Afasta os vértices de `group` ao longo das normais (camadas de brilho um fio acima da superfície)."""
        used = sorted({k for t in self.groups.get(group, []) for k in t})
        for k in used:
            p = np.array(self.v[k - 1]) + d * np.array(self.vn[k - 1])
            self.v[k - 1] = tuple(float(c) for c in p)

    def triangles(self, group):
        return len(self.groups.get(group, []))

    def write_obj(self, path, header):
        lines = ["# " + h for h in header.splitlines()]
        for p in self.v:
            lines.append("v %.4f %.4f %.4f" % p)
        for t in self.vt:
            lines.append("vt %.5f %.5f" % t)
        for n in self.vn:
            lines.append("vn %.4f %.4f %.4f" % n)
        for g in self.order:
            if not self.groups[g]:
                continue
            lines.append("g " + g)
            for a, b, c in self.groups[g]:
                lines.append("f %d/%d/%d %d/%d/%d %d/%d/%d" % (a, a, a, b, b, b, c, c, c))
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


def superellipse(n, ry, rz, e, cy=0.0, cz=0.0, start=0.0):
    """Contorno arredondado (retângulo de cantos redondos quando e > 2), n pontos, sentido anti-horário em (y, z)."""
    pts = []
    for k in range(n):
        a = start + 2 * math.pi * k / n
        c, s = math.cos(a), math.sin(a)
        y = cy + ry * math.copysign(abs(c) ** (2 / e), c)
        z = cz + rz * math.copysign(abs(s) ** (2 / e), s)
        pts.append((y, z))
    return pts
