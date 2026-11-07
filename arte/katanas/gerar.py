"""Gera as katanas do Overkill: OBJ + PNG no resource pack (assets/overkill/model/katana/) e as entradas do
kunftig/blades.json. Rodar de novo produz os mesmos arquivos (tudo é determinístico).

    python gerar.py [nome...]        # sem nomes: todas
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from katana import montar  # noqa: E402
from katanas import KATANAS  # noqa: E402
from textura import Pintor  # noqa: E402

RAIZ = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SAIDA = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "model", "katana")

CABECALHO = """Overkill: katana {nome} ({titulo})
Gerada por arte/katanas/gerar.py (arte própria do Overkill). Unidade 1/128 de bloco; ponta em -X, fio em +Y.
Grupos no formato do motor de laminas do Kunftig."""


def gerar(d):
    os.makedirs(SAIDA, exist_ok=True)
    m = montar(d)
    obj = os.path.join(SAIDA, d.nome + ".obj")
    png = os.path.join(SAIDA, d.nome + ".png")
    m.write_obj(obj, CABECALHO.format(nome=d.nome, titulo=d.textura.get("titulo", d.nome)))
    p = Pintor(d)
    p.pintar()
    p.salvar(png)
    tris = {g: m.triangles(g) for g in m.order}
    print(d.nome, tris)
    return obj, png


BLADES_JSON = os.path.join(RAIZ, "resourcepack", "assets", "overkill", "kunftig", "blades.json")


def indice():
    """O kunftig/blades.json do Overkill: as katanas e os efeitos. Os movimentos (VMD) vêm do pack da alfa."""
    laminas = {}
    for n, d in KATANAS.items():
        laminas["katana/" + n] = {
            "obj": "overkill:model/katana/%s.obj" % n,
            "textura": "overkill:model/katana/%s.png" % n,
            "porte": d.textura.get("porte", "DEFAULT"),
            "itens": ["minecraft:golden_sword"],
        }
    dados = {
        "_formato": 1,
        "_nota": "Katanas proprias do Overkill (arte/katanas). Os VMD em movimento/ vem do pack da alfa e nao sao versionados.",
        "movimento_lamina": "overkill:movimento/motion.vmd",
        "movimento_jogador": "overkill:movimento/player_motion.vmd",
        "laminas": laminas,
    }
    os.makedirs(os.path.dirname(BLADES_JSON), exist_ok=True)
    with open(BLADES_JSON, "w", encoding="utf-8") as f:
        json.dump(dados, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    nomes = sys.argv[1:] or list(KATANAS)
    for n in nomes:
        gerar(KATANAS[n])
    indice()


if __name__ == "__main__":
    main()
