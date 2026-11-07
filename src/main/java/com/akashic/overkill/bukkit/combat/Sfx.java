package com.akashic.overkill.bukkit.combat;

import com.akashic.overkill.combo.Actor;

/** The combat sounds: the pack's own (sounds.json, namespace overkill), with a vanilla stand-in until it has them. */
enum Sfx {

    SLASH("overkill:corte", "mob.irongolem.throw"),
    SLASH_MUTED("overkill:corte_leve", "random.bow"),
    CRITICAL("overkill:critico", "mob.wither.hurt"),
    SHEATH("overkill:bainha", "random.click"),
    DASH("overkill:investida", "mob.horse.leather"),
    THROW("overkill:arremesso", "random.bow"),
    STRONG("overkill:golpe_forte", "mob.zombie.wood"),
    JUDGEMENT("overkill:julgamento", "mob.endermen.portal"),
    JUDGEMENT_PULSE("overkill:julgamento_pulso", "mob.wither.hurt"),
    DRIVE_HIT("overkill:onda_acerto", "random.break"),
    SHATTER("overkill:estilhaco", "random.glass");

    final String name;
    final String fallback;

    Sfx(String name, String fallback) {
        this.name = name;
        this.fallback = fallback;
    }

    static Sfx of(Actor.Sound s) {
        switch (s) {
            case TRIDENT_THROW:
                return THROW;
            case ARMOR_EQUIP_IRON:
                return DASH;
            case ATTACK_STRONG:
                return STRONG;
            default:
                return THROW;
        }
    }
}
