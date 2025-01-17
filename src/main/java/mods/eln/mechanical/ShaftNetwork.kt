package mods.eln.mechanical

import cpw.mods.fml.common.Loader
import cpw.mods.fml.common.LoaderState
import mods.eln.Eln
import mods.eln.misc.Coordinate
import mods.eln.misc.Direction
import mods.eln.misc.INBTTReady
import mods.eln.misc.Utils
import mods.eln.node.NodeManager
import mods.eln.sim.process.destruct.DelayedDestruction
import mods.eln.sim.process.destruct.ShaftSpeedWatchdog
import mods.eln.sim.process.destruct.WorldExplosion
import net.minecraft.nbt.NBTTagCompound
import java.util.*

// Speed above which shafts will (by default) explode.
val absoluteMaximumShaftSpeed = 250.0
// "Standard" drag, in J/t per rad.
val defaultDrag = 0.02
// Energy lost due to merging, proportional to *square* of delta speed ("friction")
val energyLostPerDeltaRad = 0.05

// Would merging these two networks cause an explosion?
fun wouldExplode(a: ShaftNetwork, b: ShaftNetwork): Boolean {
    return Math.abs(a.rads - b.rads) > (50.0 - 0.1 * Math.max(a.rads, b.rads))
}


/**
 * Represents the connection between all blocks that are part of the same shaft network.
 */
open class ShaftNetwork() : INBTTReady {
    val parts = HashSet<ShaftPart>()
    var elements = HashSet<ShaftElement>()
    var _mass = 0.0
    // This has to remain overridable/RO because of things like the fixed shaft  - Grissess
    open val mass: Double
        get() {
            return if (_mass.isFinite()) _mass else 0.0
        }
    fun updateCache() {
        parts.forEach { elements.add(it.element) }
        elements.forEach { _mass += it.shaftMass }
    }

    constructor(first: ShaftElement, side: Direction) : this() {
        parts.add(ShaftPart(first, side))
        updateCache()
    }

    constructor(first: ShaftElement, sides: Iterator<Direction>) : this() {
        sides.forEach {
            parts.add(ShaftPart(first, it))
        }
        updateCache()
    }

    constructor(other: ShaftNetwork) : this() {
        takeAll(other)
    }

    fun takeAll(other: ShaftNetwork) {
        other.parts.forEach { it.element.setShaft(it.side, this) }
        parts.addAll(other.parts)
        other.parts.clear()
        updateCache()
    }

    // Aggregate properties of the (current) shaft:
    var _rads = 0.0
    open var rads: Double
        get() = if (_rads.isFinite()) _rads else 0.0
        set(v) {
            if (v.isFinite())
                _rads = v
            afterSetRads()
        }
    var radsLastPublished = rads

    var energy: Double
        get() = if (mass.isFinite() && rads.isFinite()) mass * rads * rads * 0.5 * Eln.shaftEnergyFactor else 0.0
        set(value) {
            if(value < 0 || !value.isFinite())
                rads = 0.0
            else
                rads = Math.sqrt(2 * value / ((if(mass.isFinite()) mass else 0.0) * Eln.shaftEnergyFactor))
        }

    fun afterSetRads() {
        if (_rads < 0) _rads = 0.0
        if (radsLastPublished > _rads * 1.05 || radsLastPublished < _rads * 0.95) {
            elements.forEach { it.needPublish() }
            radsLastPublished = _rads
        }
    }

    open fun hasMergePrecedenceOver(other: ShaftNetwork) = false

    /**
     * Merge two shaft networks.
     *
     * @param other The shaft network to merge into this one. Destroyed.
     */
    fun mergeShafts(other: ShaftNetwork, invoker: ShaftElement?): ShaftNetwork {
        assert(other != this)
        Utils.println(String.format("SN.ms(this=%s, %s, invoker=%s)", this, other, invoker))

        // If the other class wants to take this merge, let it.
        // In particular, don't presume that:
        // (1) setShaft won't be called on the invoker during the merge, and
        // (2) that the invoker will have the same shaft afterward
        if(other.hasMergePrecedenceOver(this)) {
            Utils.println(String.format("SN.mS: merge prec %s over %s", other, this))
            return other.mergeShafts(this, invoker)
        }

        /* XXX (Grissess): While loading the map, shaft networks are repeatedly
        merged and deserialized, causing them to lose energy just as if the
        components were newly added. This can cause, in the worst case, saved
        networks to explode on load. Although a bit of a hack, asking the FML
        Loader about which state it's in seems to be the best workaround. At
        some point, consider serializing network connectivity properly.
         */

        val loadMerge = Loader.instance().loaderState == LoaderState.SERVER_ABOUT_TO_START
        // val loadMerge = false
        // Utils.println("SN.mS: state " + Loader.instance().loaderState.name)

        // Utils.println(String.format("SN.mS: Merging %s r=%f e=%f, %s r=%f e=%f, loading=%s", this, rads, energy, other, other.rads, other.energy, loadMerge))

        var deltaRads = 0.0
        var newEnergy = 0.0
        if(!loadMerge) {
            deltaRads = Math.abs(rads - other.rads)

            if (wouldExplode(this, other) && invoker != null) {
                Utils.println(String.format("SN.mS: Bad matching, %s will explode", invoker))
                DelayedDestruction(
                    WorldExplosion(invoker.coordonate()).machineExplosion(),
                    0.0  // Sooner than later, just not right now :)
                )
                // Continue, however. The networks will unmerge when a component disappears, but assume they might not.
            }

            newEnergy = energy + other.energy
        }


        for (part in other.parts) {
            parts.add(part)
            part.element.setShaft(part.side, this)
        }
        other.parts.clear()
        updateCache()
        other.updateCache()

        if(!loadMerge) {
            energy = newEnergy - energyLostPerDeltaRad * deltaRads * deltaRads
        }

        // Utils.println(String.format("SN.mS: Result %s r=%f e=%f", this, rads, energy))

        // Return the survivor
        return this
    }

    /**
     * Connect a ShaftElement to a shaft network, merging any relevant adjacent networks.
     * @param from The ShaftElement that changed.
     */
    fun connectShaft(from: ShaftElement, side: Direction) {
        assert(ShaftPart(from, side) in parts)
        val neighbours = getNeighbours(from)
        for (neighbour in neighbours) {
            if(neighbour.thisShaft != this) {
                Utils.println("SN.cS: WARNING: Connecting part with this != getShaft(side)")
                continue
            }
            if (neighbour.otherShaft != null && neighbour.otherShaft != this) {
                mergeShafts(neighbour.otherShaft, from)

                // Inform the neighbour and the element itself that its shaft connectivity has changed.
                neighbour.makeConnection()
            }
        }
    }

    /**
     * Disconnect from a shaft network, because an element is dying.
     * @param from The IShaftElement that's going away.
     */
    fun disconnectShaft(from: ShaftElement) {
        // Inform all directly involved shafts about the change in connections.
        for (neighbour in getNeighbours(from)) {
            if(neighbour.thisShaft == this) {
                neighbour.breakConnection()
                // Going away momentarily, but...
                from.setShaft(neighbour.thisPart.side, ShaftNetwork(from, neighbour.thisPart.side))
            }
        }

        parts.removeIf {
            it.element == from
        }

        // This may have split the network.
        // At the moment there's no better way to figure this out than by exhaustively walking it to check for
        // partitions. Basically fine, as they don't get very large, but a possible target for optimization later on.
        rebuildNetwork()

    }

    /**
     * Walk the entire network, splitting as necessary.
     * Yes, this makes breaking a shaft block O(n). Not a problem right now.
     */
    internal fun rebuildNetwork() {
        val unseen = HashSet<ShaftPart>(parts)
        val queue = HashMap<ShaftPart,ShaftNetwork>()
        val seen = HashSet<ShaftPart>()
        val curRads = if(rads.isNaN()) 0.0 else rads
        var shaft = ShaftNetwork()
        shaft.rads = curRads
        // Utils.println("SN.rN ----- START -----")
        while (unseen.size > 0) {
            shaft.parts.clear();
            // Do a breadth-first search from an arbitrary element.
            val start = unseen.iterator().next()
            unseen.remove(start);
            if(!(start in seen)) queue.put(start, shaft)
            while (queue.size > 0) {
                val next = queue.iterator().next()
                queue.remove(next.key);
                seen.add(next.key)
                shaft = next.value
                if(next.key.element.isDestructing()) continue
                shaft.parts.add(next.key);
                next.key.element.setShaft(next.key.side, shaft)
                // Utils.println("SN.rN visit next = " + next + ", queue.size = " + queue.size)
                for(side in next.key.element.shaftConnectivity) {
                    val part = ShaftPart(next.key.element, side)
                    if(!(part in seen))
                        queue.put(part, next.key.element.getShaft(side) ?: shaft)
                }
                val neighbours = getNeighbours(next.key.element)
                for (neighbour in neighbours) {
                    unseen.remove(neighbour.otherPart)
                    if(!(neighbour.otherPart in seen)) {
                        queue.put(neighbour.otherPart, neighbour.thisShaft!!)
                    }
                }
            }

            // Utils.println("SN.rN new shaft, unseen.size = " + unseen.size)
            // We ran out of network. Any elements remaining in unseen should thus form a new network.
            shaft.updateCache()
            shaft.elements.forEach { it.needPublish() }
            shaft = ShaftNetwork()
            shaft.rads = curRads
        }

        // Before we exit, make sure the last shaft constructed has its cache rebuilt.
        shaft.updateCache()
        shaft.elements.forEach { it.needPublish() }

        // At this point, it's likely that `this` is no longer referenced by
        // any object and will be dropped on return.

        // Utils.println("SN.rN ----- FINISH -----")
    }

    private fun getNeighbours(from: ShaftElement): ArrayList<ShaftNeighbour> {
        val c = Coordinate()
        val ret = ArrayList<ShaftNeighbour>(6)
        for (dir in from.shaftConnectivity) {
            c.copyFrom(from.coordonate())
            c.move(dir)
            val to = NodeManager.instance!!.getTransparentNodeFromCoordinate(c)
            if (to is ShaftElement) {
                for (dir2 in to.shaftConnectivity) {
                    if (dir2.inverse == dir) {
                        ret.add(ShaftNeighbour(
                            ShaftPart(from, dir),
                            from.getShaft(dir),
                            dir,
                            ShaftPart(to, dir2),
                            to.getShaft(dir2)
                        ))
                        break
                    }
                }
            }
        }
        return ret
    }

    override fun readFromNBT(nbt: NBTTagCompound, str: String) {
        rads = nbt.getFloat(str + "rads").toDouble()
        if(!rads.isFinite()) rads = 0.0
        // Utils.println(String.format("SN.rFN: load %s r=%f", this, rads))
    }

    override fun writeToNBT(nbt: NBTTagCompound, str: String) {
        nbt.setFloat(str + "rads", rads.toFloat())
        // Utils.println(String.format("SN.wTN: save %s r=%f", this, rads))
    }

}

class StaticShaftNetwork() : ShaftNetwork() {

    constructor(elem: ShaftElement, dirs: Iterator<Direction>) : this() {
        dirs.forEach {
            parts.add(ShaftPart(elem, it))
        }
    }
    var fixedRads = 0.0

    override var rads
        get() = fixedRads
        set(_) {}

    // XXX This shouldn't matter...
    override val mass: Double
        get() = 1000.0

    override fun hasMergePrecedenceOver(other: ShaftNetwork) = other !is StaticShaftNetwork
}

interface ShaftElement {
    val shaftMass: Double
    val shaftConnectivity: Array<Direction>
    fun coordonate(): Coordinate
    fun getShaft(dir: Direction): ShaftNetwork?
    fun setShaft(dir: Direction, net: ShaftNetwork?)
    fun isInternallyConnected(a: Direction, b: Direction): Boolean = true
    fun isDestructing(): Boolean

    fun initialize() {
        shaftConnectivity.forEach {
            val shaft = getShaft(it)
            if(shaft != null) shaft.connectShaft(this, it)
        }
    }

    fun needPublish()

    fun connectedOnSide(direction: Direction, net: ShaftNetwork) {}

    fun disconnectedOnSide(direction: Direction, net: ShaftNetwork?) {}
}

fun createShaftWatchdog(shaftElement: ShaftElement): ShaftSpeedWatchdog {
    return ShaftSpeedWatchdog(shaftElement, absoluteMaximumShaftSpeed)
}

data class ShaftPart(
    val element: ShaftElement,
    val side: Direction
)

data class ShaftNeighbour(
    val thisPart: ShaftPart,
    val thisShaft: ShaftNetwork?,
    val side: Direction,
    val otherPart: ShaftPart,
    val otherShaft: ShaftNetwork?
) {
    fun makeConnection() {
        val thisNet = thisPart.element.getShaft(thisPart.side)
        val otherNet = otherPart.element.getShaft(otherPart.side)
        if(thisNet != otherNet) Utils.println("ShaftNeighbour.makeConnection: WARNING: Not actually connected?")
        thisPart.element.connectedOnSide(thisPart.side, thisNet!!)
        otherPart.element.connectedOnSide(otherPart.side, otherNet!!)
    }

    fun breakConnection() {
        val thisNet = thisPart.element.getShaft(thisPart.side)
        val otherNet = otherPart.element.getShaft(otherPart.side)
        if(thisNet != otherNet) Utils.println("ShaftNeighbour.breakConnection: WARNING: Break already broken connection?")
        thisPart.element.disconnectedOnSide(thisPart.side, thisNet)
        otherPart.element.disconnectedOnSide(otherPart.side, otherNet)
        // TODO: Unmerge networks here eventually?
    }
}
