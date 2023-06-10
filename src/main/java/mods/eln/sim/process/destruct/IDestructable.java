package mods.eln.sim.process.destruct;

public interface IDestructable {
    void destructImpl();
    void failureTick();

    void failureCancel();

    String describe();
}
