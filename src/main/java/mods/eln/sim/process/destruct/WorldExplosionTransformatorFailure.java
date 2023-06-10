package mods.eln.sim.process.destruct;

import mods.eln.Eln;
import mods.eln.gridnode.electricalpole.ElectricalPoleElement;
import mods.eln.gridnode.transformer.GridTransformerElement;
import mods.eln.misc.Coordinate;
import mods.eln.node.six.SixNodeElement;
import mods.eln.node.transparent.TransparentNodeElement;
import mods.eln.sim.mna.SubSystem;
import mods.eln.simplenode.energyconverter.EnergyConverterElnToOtherNode;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;

public class WorldExplosionTransformatorFailure implements IDestructable {

    Object origine;

    Coordinate c;
    float strength;
    String type;

    public WorldExplosionTransformatorFailure(Coordinate c) {
        this.c = c;
    }

    public WorldExplosionTransformatorFailure(SixNodeElement e) {
        this.c = e.getCoordinate();
        this.type = e.toString();
        origine = e;
    }

    public WorldExplosionTransformatorFailure(TransparentNodeElement e) {
        this.c = e.coordinate();
        this.type = e.toString();
        origine = e;
    }

    public WorldExplosionTransformatorFailure(EnergyConverterElnToOtherNode e) {
        this.c = e.coordinate;
        this.type = e.toString();
        origine = e;
    }

    public WorldExplosionTransformatorFailure cableExplosion() {
        strength = 1.5f;
        return this;
    }

    public WorldExplosionTransformatorFailure machineExplosion() {
        strength = 3;
        return this;
    }

    @Override
    public void destructImpl() {
        //NodeManager.instance.removeNode(NodeManager.instance.getNodeFromCoordonate(c));

        if (Eln.instance.explosionEnable)
            c.world().createExplosion((Entity) null, c.x, c.y, c.z, strength, true);
        else
            c.world().setBlock(c.x, c.y, c.z, Blocks.air);
    }

    double originalRatio;

    @Override
    public void failureTick() {
        // we'll start fricking with the voltages here to simulate a transformer failure
        if(originalRatio == 0) {
            if(origine instanceof ElectricalPoleElement) {

                ElectricalPoleElement pole = (ElectricalPoleElement) origine;
                originalRatio = pole.getTrafo().component4().getRatio();
            }
            if(origine instanceof GridTransformerElement) {
                GridTransformerElement transformer = (GridTransformerElement) origine;
                originalRatio = transformer.getInterSystemProcess().getRatio();
            }
        }

        if(origine instanceof ElectricalPoleElement) {

            ElectricalPoleElement pole = (ElectricalPoleElement) origine;
            pole.getTrafo().component4().setRatio(pole.getTrafo().component4().getRatio() * (Math.random() * 0.6 + 0.7));
        }
        if(origine instanceof GridTransformerElement) {
            GridTransformerElement transformer = (GridTransformerElement) origine;
            transformer.getInterSystemProcess().setRatio(transformer.getInterSystemProcess().getRatio() * (Math.random() * 0.6 + 0.7));
        }
    }

    @Override
    public void failureCancel() {
        if(origine instanceof ElectricalPoleElement) {
            ElectricalPoleElement pole = (ElectricalPoleElement) origine;
            pole.getTrafo().component4().setRatio(originalRatio);
        }
    }

    @Override
    public String describe() {
        return String.format("%s (%s)", this.type, this.c.toString());
    }
}
