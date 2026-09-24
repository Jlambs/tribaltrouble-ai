package com.oddlabs.tt.player.ultra;

import org.jspecify.annotations.NonNull;

/** Large island 1v1: greedy three-quarters opening, forward armory hub near iron, iron + rubber warriors. */
final class LargeIslandStrategy extends Strategy {
    LargeIslandStrategy(@NonNull Params params) {
        super(params);
    }

    @Override
    @NonNull
    String name() {
        return "large";
    }

    @Override
    int @NonNull [] openingOrder(boolean threatened) {
        int q = threatened ? Math.min(2, i("openingQuarters")) : i("openingQuarters");
        return switch (Math.max(0, Math.min(4, q))) {
            case 0 -> new int[]{A, Q, Q};
            case 1 -> new int[]{Q, A, Q};
            case 2 -> new int[]{Q, Q, A};
            case 3 -> new int[]{Q, Q, Q, A};
            default -> new int[]{Q, Q, Q, Q, A};
        };
    }

    @Override
    protected void defineDefaults() {
        // --- economy ---
        def("openingQuarters", 4);
        def("quartersMax", 6);
        def("nKeepOpening", 2);
        def("nKeepBoom", 16);
        def("nKeepCap", 1);
        def("capMargin", 80);
        def("builderFracBoom", 0.5f);
        def("builderCap", 0.6f);
        def("stockWood", 12);
        def("stockIron", 6);
        def("stockRock", 4);
        def("stockTau", 60);
        def("eMinFloor", 25);
        def("eMinFrac", 0.12f);
        def("hunterMax", 8);
        def("chickenDist", 90);
        def("chickenDistFar", 150);
        def("workersKeepFrac", 0f);
        def("woodReserve", 20);
        def("woodReserveTime", 240);
        def("ironReach", 90);
        def("rockMix", 1f);
        // --- building ---
        def("foundationLead", 2);
        def("prechopPerTree", 3);
        def("jWalk", 0.3f);
        def("jDangerR", 0.40f);
        def("hubRadius", 170);
        def("q1Radius", 80);
        def("towerBuilders", 6);
        def("buildersBoomSite", 12);
        def("woodDrop", 1);
        def("dropWaitBuilders", 6);
        def("pathAware", 1);
        def("expansion", 1);
        def("weaponBuffer", 0);
        def("enemyChiefFactor", 0f);
        def("ironMinNodes", 12);
        def("rockMinNodes", 4);
        def("nodeCap", 6);
        def("enemyLatentGuess", 4f);
        def("attackLearn", 1);
        def("surplusOut", 1);
        def("stunTowerFocus", 1);
        def("tootTowerValue", 4f);
        def("expandFront", 0.2f);
        def("towerLinear", 0f);
        def("coverWeight", 2f);
        def("stunSiege", 1);
        def("dodge", 1);
        def("dodgeMin", 10f);
        def("dodgeMax", 24f);
        def("dodgeTo", 23f);
        def("dodgeHold", 3.9f);
        def("dodgePeons", 0);
        def("dodgeSmart", 0);
        def("tootAfterEnemy", 0);
        def("unfreeze", 0);
        def("deployThreatR", 30);
        def("assaultGate", 0);
        def("assaultRatio", 1.5f);
        def("towerLinW", 0.4f);
        def("assaultR", 24);
        def("assaultChief", 4f);
        def("assaultMemory", 240f);
        def("approachTowers", 0);
        def("frozenWindow", 0);
        def("tootGrace", 4.5f);
        def("frozenMin", 5);
        def("frozenReengage", 1.0f);
        def("tootBaseCover", 0);
        def("towerOpenMax", 0);
        def("towerOpenHot", 2);
        def("towerPrioLow", 0);
        def("qbFixed", 1);
        def("qbPrio", 500);
        def("unplacedCrew", 0);
        def("ironDryRock", 0);
        def("ironDryDelay", 20f);
        def("lateTowers", 0);
        def("lateTowerTime", 900f);
        def("lateReserve", 2);
        def("learnDecay", 0f);
        def("rockEff", 0f);
        def("deployGate", 0);
        def("towerThreatR", 0);
        def("towerSchedule", 0);
        def("towerEarlyCap", 2);
        def("towerEarlyTime", 480f);
        def("towerEarlyQuarters", 4);
        def("siegeEvac", 0);
        def("siegeHp", 0.6f);
        def("evacKeep", 4);
        def("rockFallback", 0);
        def("marchWatch", 0);
        // Heavily outnumbered (1 vs 3 or more): keep peons out of a besieged armory, rock defenders when iron is out,
        // and the march watchdog. Neutral or worse in 1v1 (A/B), clearly better against several allied opponents.
        def("multiN", 3);
        defMulti("siegeEvac", 1);
        defMulti("rockFallback", 1);
        defMulti("marchWatch", 1);
        def("expandIron", 0f);
        def("armoryIronUnits", 150);
        def("tootCloseCast", 1);
        def("tootPreempt", 1f);
        def("chiefTargetValue", 2.5f);
        def("safeSwitch", 1);
        // --- military ---
        def("attackMin", 12);
        def("commitRatio", 1.15f);
        def("grindRatio", 0.95f);
        def("retreatRatio", 0.6f);
        def("defendRadius", 34);
        def("reinforceBatch", 5);
        def("engageRange", 14);
        def("cohesionFrac", 0.75f);
        def("legLength", 26);
        def("capAttackFrac", 0.9f);
        def("corps", 1);
        def("micro", 1);
        def("raid", 1);
        def("recall", 1);
        def("peonStrike", 1);
        def("raidSize", 4);
        def("corpsSize", 16);
        def("creep", 1);
        def("creepMax", 3);
        def("proxyTower", 0);
        def("proxyTime", 300);
        def("proxyMax", 1);
        // --- towers ---
        def("c1Time", 200);
        def("c2Time", 330);
        def("towerMax", 8);
        def("towerRadius", 10);
        // --- chieftain ---
        def("chiefTime", 180);
        def("chiefMinQuarters", 3);
        def("chiefKeep", 6);
        def("tootTheta", 5);
    }
}
