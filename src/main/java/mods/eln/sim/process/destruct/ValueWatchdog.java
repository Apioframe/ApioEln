package mods.eln.sim.process.destruct;

import mods.eln.*;
import mods.eln.misc.Utils;
import mods.eln.sim.IProcess;

public abstract class ValueWatchdog implements IProcess {

    IDestructable destructable;
    double perOverflowStrenght = 1;
    double min;
    double max;
    double overflowTolerance;

    double timeoutReset = 2;

    double timeout = 0;
    boolean boot = true;
    boolean joker = true;
    boolean overflowfailing = false;

    double rand = Utils.rand(0.5, 1.5);

    @Override
    public void process(double time) {
        if (boot) {
            boot = false;
            timeout = timeoutReset;
        }
        double value = getValue();
        double overflow = Math.max(value - max, min - value);
        double overflowTolerated = Math.max(overflow - overflowTolerance, 0);
        if (overflowTolerated > 0) {
            if (joker) {
                joker = false;
                overflow = 0;
            }
        } else {
            joker = true;
        }

        timeout -= time * overflowTolerated * rand;
        if (timeout > timeoutReset) {
            timeout = timeoutReset;
        }
        if (timeout < 0) {
            Utils.println("%s destroying %s",
                getClass().getName(),
                destructable.describe());
            if (!Eln.debugExplosions)
                destructable.destructImpl();
        }
        if(overflow > 0) {
            System.out.println("Failure tick");
            destructable.failureTick();
            overflowfailing = true;
        } else {
            if(overflowfailing) {
                destructable.failureCancel();
                overflowfailing = false;
            }
        }
    }

    public ValueWatchdog set(IDestructable d) {
        this.destructable = d;
        return this;
    }

    abstract double getValue();

    public void disable() {
        this.max = 100000000;
        this.min = -max;
        this.timeoutReset = 10000000;
    }

    public void reset() {
        boot = true;
    }
}
