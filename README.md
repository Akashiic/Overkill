# Overkill

Estudo e roadmap para levar o **SlashBlade** (mod de katanas) com **combate estilo Devil May Cry** para o **Minecraft 1.7.10**, como **plugin Bukkit + resource pack + mod mínimo de cliente**, por cima do [Künftig](https://github.com/Akashiic/Kunftig) e do GeckoLib Unofficial.

> Estado: **fase de estudo**. Ainda não há código. Este repositório guarda as decisões de arquitetura, o que já existe no stack, o que falta e o roadmap.

## Documentos

| Arquivo | Conteúdo |
|---|---|
| [`docs/ESTUDO.md`](docs/ESTUDO.md) | Viabilidade, mapeamento SlashBlade/DMC → Künftig, lacunas, riscos, licenças e roadmap em fases |
| [`docs/REFERENCIAS.md`](docs/REFERENCIAS.md) | Fontes consultadas (repositórios, páginas de mods, documentação do Künftig) |

## Resumo em cinco linhas

1. O SlashBlade original já existe na 1.7.10 (r87/r88). O objetivo aqui é levar as **ideias** do SlashBlade_2 (lâminas por dados, slash arts, special effects, combos) e o combate estilo DMC para o stack 1.7.10 do Gabriel, não portar código linha a linha.
2. Arquitetura: **plugin Bukkit** (lógica, dano, rank, validação) + **mod Künftig no cliente** (renderização GeckoLib, entrada, pacotes) + **resource pack** (modelos, animações, texturas, sons).
3. O Künftig já cobre: modelos 3D animados na mão, estados de animação enviados pelo servidor, PDC em itens, displays 3D no mundo e partículas modernas.
4. Faltam no Künftig: canal de entrada cliente→servidor, previsão de golpe no cliente para espadas, HUD de rank e (mais tarde) lock-on.
5. O maior custo é **arte** (animações de combo no Blockbench), não código.

## Licença

Ainda não escolhida. O Künftig também está com a licença "not chosen yet"; vale decidir as duas juntas. Ver a seção de licenças em [`docs/ESTUDO.md`](docs/ESTUDO.md).
