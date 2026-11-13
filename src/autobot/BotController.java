package autobot;

import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Call;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public class BotController {
    public static boolean enabled = false;
    private static float logicTimer = 0f;

    public static final float TARGET_RATE = 10.0f;

    private static MiningManager.VeinDevelopmentProject pendingProject = null;

    public static void setEnabled(boolean state) {
        enabled = state;
        pendingProject = null;
        if (enabled) {
            LogisticsManager.reset();
            BotLogger.info("Bot enabled by user.");
            BotUI.notify("[green]AutoBot enabled! Check console (~) for logs.[]");
        } else {
            if (Vars.player != null && Vars.player.unit() != null) {
                Vars.player.unit().clearBuilding();
                Vars.player.unit().mineTile = null;
            }
            BotLogger.info("Bot disabled.");
            BotUI.notify("[scarlet]AutoBot disabled[]");
        }
    }

    public static void update() {
        Unit unit = Vars.player.unit();
        CoreBuild core = Vars.player.core();
        if (unit == null || core == null) return;

        // 1. ЕСЛИ У ЮНИТА ЕСТЬ АКТИВНЫЕ ПЛАНЫ СТРОИТЕЛЬСТВА -> ЛЕТИМ И СТРОИМ
        if (!unit.plans.isEmpty()) {
            unit.mineTile = null;
            BuildPlan currentPlan = unit.plans.first();
            float targetX = currentPlan.drawx();
            float targetY = currentPlan.drawy();
            float dst = unit.dst(targetX, targetY);

            if (dst > 160f) {
                Vec2 moveVec = new Vec2(targetX - unit.x, targetY - unit.y).setLength(unit.speed());
                unit.moveAt(moveVec);
                unit.lookAt(targetX, targetY);
                BotLogger.state("Flying to build site (" + currentPlan.x + ", " + currentPlan.y + "), plans left: " + unit.plans.size);
            } else {
                BotLogger.state("Building (" + currentPlan.block.name + "), plans left: " + unit.plans.size);
            }
            return;
        }

        // 2. ЕСЛИ ЕСТЬ ОТЛОЖЕННЫЙ ПРОЕКТ, НА КОТОРЫЙ МЫ КОПИМ РЕСУРСЫ ПЕРЕД СТРОЙКОЙ
        if (pendingProject != null) {
            int coreCopper = core.items.get(Items.copper);
            int coreLead = core.items.get(Items.lead);

            // Если в ядре уже хватает ресурсов -> запускаем стройку
            if (coreCopper >= pendingProject.copperCost && coreLead >= pendingProject.leadCost) {
                unit.mineTile = null;
                pendingProject.commitToUnit(unit);
                pendingProject = null;
                return;
            }

            // Определяем, какого ресурса сейчас не хватает в ядре
            Item missingItem = (coreCopper < pendingProject.copperCost) ? Items.copper : Items.lead;
            int coreHave = (missingItem == Items.copper) ? coreCopper : coreLead;
            int totalNeed = (missingItem == Items.copper) ? pendingProject.copperCost : pendingProject.leadCost;

            int inHand = unit.hasItem() ? unit.stack.amount : 0;
            Item handItem = unit.hasItem() ? unit.stack.item : null;

            // Проверяем, пора ли нести добытую руду к ядру и разгружать её:
            // 1) В руках лежит ДРУГОЙ ресурс (мешает копать нужный)
            // 2) Инвентарь юнита заполнен (или в сумме "ядро + руки" уже хватает на весь проект)
            boolean wrongItemInHand = (handItem != null && handItem != missingItem);
            boolean enoughWithHand = (handItem == missingItem && (coreHave + inHand) >= totalNeed);
            boolean inventoryFull = (inHand >= unit.itemCapacity());

            if (wrongItemInHand || enoughWithHand || inventoryFull) {
                unit.mineTile = null;
                float dstToCore = unit.dst(core.x, core.y);
                // Радиус передачи предметов в ядро = Vars.itemTransferRange (обычно 100f)
                if (dstToCore > Vars.itemTransferRange - 15f) {
                    Vec2 moveVec = new Vec2(core.x - unit.x, core.y - unit.y).setLength(unit.speed());
                    unit.moveAt(moveVec);
                    unit.lookAt(core.x, core.y);
                    BotLogger.state("Carrying " + inHand + " " + (handItem != null ? handItem.name : "") + " back to core...");
                } else {
                    // Разгружаем инвентарь юнита в ядро
                    Call.transferInventory(Vars.player, core);
                    BotLogger.state("Deposited resources into core.");
                }
                return;
            }

            // Иначе летим к ближайшей жиле недостающего ресурса и копаем
            Tile oreTile = MiningManager.findNearestOreTile(missingItem, core);
            if (oreTile != null) {
                float dstToOre = unit.dst(oreTile.worldx(), oreTile.worldy());
                if (dstToOre > 55f) {
                    unit.mineTile = null;
                    Vec2 moveVec = new Vec2(oreTile.worldx() - unit.x, oreTile.worldy() - unit.y).setLength(unit.speed());
                    unit.moveAt(moveVec);
                    unit.lookAt(oreTile.worldx(), oreTile.worldy());
                } else {
                    unit.mineTile = oreTile;
                }
                BotLogger.state("Mining " + missingItem.name + " (Core: " + coreHave + "/" + totalNeed + ", Hand: " + inHand + "/" + unit.itemCapacity() + ")");
            } else {
                BotLogger.state("Waiting for " + missingItem.name + " (" + coreHave + "/" + totalNeed + ") in core...");
            }
            return;
        }

        unit.mineTile = null;

        // 3. ПЛАНИРОВАНИЕ СЛЕДУЮЩЕЙ ЖИЛЫ (раз в 1 секунду / 60 тиков)
        logicTimer += Time.delta;
        if (logicTimer < 60f) return;
        logicTimer = 0f;

        float copperRate = MiningManager.calculateProductionRate(Items.copper);
        float leadRate = MiningManager.calculateProductionRate(Items.lead);

        if (copperRate < TARGET_RATE) {
            float needed = TARGET_RATE - copperRate;
            BotLogger.state("Copper production is " + String.format("%.2f", copperRate) + "/" + TARGET_RATE + "/s. Calculating next copper vein...");
            MiningManager.VeinDevelopmentProject project = MiningManager.planNextVein(Items.copper, core, needed);
            if (project != null) {
                BotLogger.info("Planned " + project.summary + " -> Required: " + project.copperCost + " Copper, " + project.leadCost + " Lead.");
                pendingProject = project;
            } else {
                BotLogger.state("No valid copper veins found or path blocked!");
            }
            return;
        }

        if (leadRate < TARGET_RATE) {
            float needed = TARGET_RATE - leadRate;
            BotLogger.state("Lead production is " + String.format("%.2f", leadRate) + "/" + TARGET_RATE + "/s. Calculating next lead vein...");
            MiningManager.VeinDevelopmentProject project = MiningManager.planNextVein(Items.lead, core, needed);
            if (project != null) {
                BotLogger.info("Planned " + project.summary + " -> Required: " + project.copperCost + " Copper, " + project.leadCost + " Lead.");
                pendingProject = project;
            } else {
                BotLogger.state("No valid lead veins found or path blocked!");
            }
            return;
        }

        BotLogger.state("STAGE 1 COMPLETE! Copper: " + String.format("%.2f", copperRate) + "/s, Lead: " + String.format("%.2f", leadRate) + "/s. Ready for Power & Silicon!");
    }
}
