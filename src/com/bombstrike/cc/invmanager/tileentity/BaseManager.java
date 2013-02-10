package com.bombstrike.cc.invmanager.tileentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;

import com.bombstrike.cc.invmanager.InventoryManager;
import com.bombstrike.cc.invmanager.Utils;
import com.bombstrike.cc.invmanager.compat.ComputerCraft;
import com.bombstrike.cc.invmanager.compat.ComputerCraft.Task;

import dan200.computer.api.IComputerAccess;
import dan200.computer.api.IPeripheral;

public class BaseManager extends TileEntity implements IPeripheral {
	/**
	 * This is the queue of calls waiting to be run in the main thread
	 */
	protected ConcurrentLinkedQueue<Task> callQueue;
	/**
	 * This map contains the list of computers currently connected to this device
	 */
	protected Map<Integer, IComputerAccess> computers = null;
	/**
	 * This is the common interface shared by CC computers
	 */
	Class<?> computerEntityInterface = null;
	/**
	 * This is an instance of our ComputerCraft class, that takes care of running
	 * the actual methods
	 */
	protected ComputerCraft cc = null;
	/**
	 * A bitfield listing every nearby computers
	 */
	protected int computerConnections = 0;
	/**
	 * The same with chests
	 */
	protected int chestConnections = 0;
	/**
	 * This is the list of methods that are provided to ComputerCraft
	 */
	final static protected String[] methodList = {
			"size",
			"read",
			"move"
	};

	public BaseManager() {
		// attach and detach are called from the LUA thread, so we need a thread
		// safe class
		computers = new ConcurrentHashMap<Integer, IComputerAccess>();
		callQueue = new ConcurrentLinkedQueue<Task>();
		
		// try and retrieve that interface
		try {
			computerEntityInterface = Class.forName("dan200.computer.shared.IComputerEntity");
		} catch (Exception e) {
			return;
		}
	}
	
	/**
	 * This method returns a valid inventory for the string provided
	 * @param name Either one of "down", "up", "north", "south", "east", "west" and for the plate: "player"
	 * @return null if no inventory is found, otherwise an instance of IInventory
	 * @throws Exception
	 */
	public IInventory resolveInventory(String name) throws Exception {
		// the plate is only compatible with player and down directions
		if (!name.equals("player")) {
			return Utils.getInventory(this, name);
		}

		return null;
	}

	/**
	 * Called every few ticks, run every computer task waiting to be run
	 */
	@Override
	public void updateEntity() {
		super.updateEntity();
		
		Task task;
		while ((task = callQueue.poll()) != null) {
			Map<String, Object> result = new HashMap<String, Object>();
			try {
				result.put("result", "success");
				result.put("data", task.call());
			} catch (Exception e) {
				result.put("result", "failure");
				result.put("message", e.getMessage());
			}
			queueEvent("invmanager_task", new Object[]{result}, task.getComputer());
		}
	}

	/**
	 * Queue a piece of code to be called at the next entity update
	 * @param callable
	 */
	public void queueCallable(IComputerAccess computer, Callable<Object> callable) {
		callQueue.add(cc.new Task(computer, callable));
	}
	
	/**
	 * Queue an event with no arguments on all connected computers
	 * @param event The event name
	 */
	public void queueEvent(String event) {
		queueEvent(event, null);
	}
	
	/**
	 * Queue an event with the given arguments on all connected computers
	 * @param event The event name
	 * @param argument Any of the Lua compatible object
	 */
	public void queueEvent(String event, Object[] arguments) {
		queueEvent(event, arguments, null);
	}
	
	/**
	 * Queue an event with the given arguments with either the target computer, or every
	 * computers if set to null
	 * @param event
	 * @param arguments
	 * @param targetComputer null to send to all computers, or a valid IComputerAccess instance
	 */
	public void queueEvent(String event, Object[] arguments, IComputerAccess targetComputer) {
		if (targetComputer == null) {
			for (IComputerAccess computer : computers.values()) {
				computer.queueEvent(event, arguments);
			}
		} else {
			targetComputer.queueEvent(event, arguments);
		}
	}
	
	/**
	 * Recompute the list of neighbor computers and chests
	 * @param world
	 * @param x
	 * @param y
	 * @param z
	 */
	public boolean recomputeConnections() {
		// don't recompute anything on the client
		if (worldObj.isRemote) return false;

		boolean update = false;
		if (computerEntityInterface != null) {
			// search for nearby computers
			int detectedComputerConnections = 0;
			int detectedChestConnections = 0;
			int shift = 0;
	
			for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
				TileEntity te = Utils.getTileNeighbor(worldObj, xCoord, yCoord, zCoord, direction);
				if (te != null) {
					if (computerEntityInterface.isAssignableFrom(te.getClass())) {
						// create connection
						detectedComputerConnections |= 0x1 << direction.ordinal();
					}
					if (te instanceof IInventory) {
						detectedChestConnections |= 0x1 << direction.ordinal();
					}
				}
				shift++;
			}
			if (computerConnections != detectedComputerConnections) {
				computerConnections = detectedComputerConnections;
				update = true;
			}
			if (chestConnections != detectedChestConnections) {
				chestConnections = detectedChestConnections;
				update = true;
			}
		}

		return update;
	}
	
	public int getComputerConnections() {
		return computerConnections;
	}
	
	public int getChestConnections() {
		return chestConnections;
	}
	
	@Override
	public Packet getDescriptionPacket() {
		NBTTagCompound data = new NBTTagCompound("data");
		writeToNBT(data);
		Packet132TileEntityData packet = new Packet132TileEntityData(xCoord, yCoord, zCoord, 0, data);
		
		return packet;
	}
	
	@Override
	public void onDataPacket(INetworkManager net, Packet132TileEntityData packet) {
		super.onDataPacket(net, packet);
		readFromNBT(packet.customParam1);
		// the rendering depends on some states we have, update the block
		worldObj.markBlockForRenderUpdate(xCoord, yCoord, zCoord);
	}
	
	@Override
	public void writeToNBT(NBTTagCompound nbtData) {
		nbtData.setInteger("computerConnections", computerConnections);
		nbtData.setInteger("chestConnections", chestConnections);
		super.writeToNBT(nbtData);
	}
	
	@Override
	public void readFromNBT(NBTTagCompound nbtData) {
		super.readFromNBT(nbtData);
		if (nbtData.hasKey("computerConnections")) {
			computerConnections = nbtData.getInteger("computerConnections");
		}
		if (nbtData.hasKey("chestConnections")) {
			chestConnections = nbtData.getInteger("chestConnections");
		}
	}
	
	/*********************************
	 *  IPeripheral Implementation
	 *********************************/

	@Override
	public String getType() {
		return "InventoryManager";
	}

	@Override
	public String[] getMethodNames() {
		return BaseManager.methodList;
	}

	@Override
	public Object[] callMethod(IComputerAccess computer, int method,
			final Object[] arguments) throws Exception {
		// lazy constructor
		if (cc == null) cc = new ComputerCraft(this);
		// resolve calls
		Callable<Object> callable;
		switch (method) {
		case 0: // size
			callable = new Callable<Object>() {
				@Override public Object call() throws Exception { return cc.size(arguments); }
			};
			break;
		case 1: // inventory <int:slot>
			callable = new Callable<Object>() {
				@Override public Object call() throws Exception { return cc.read(arguments); }
			};
			break;
		/*case 2: // equipped
			if (!isPlayerOn()) throw new Exception("no player connected");
			callable = new Callable<Object[]>() {
				@Override public Object[] call() throws Exception { return new Integer[]{player.inventory.currentItem}; }
			};
			break;*/
		case 2: // move
			callable = new Callable<Object>() {
				@Override public Object call() throws Exception { return cc.move(arguments); }
			};
			break;
		default:
			throw new Exception("unknown method");
		}

		queueCallable(computer, callable);
		return null;
	}

	@Override
	public boolean canAttachToSide(int side) {
		// the default implementation accept connections from everywhere
		return true;
	}

	@Override
	public void attach(IComputerAccess computer) {
		computers.put(computer.getID(), computer);
		computer.mountFixedDir("invmanager", "mods/invmanager-lua", true, 0);
	}

	@Override
	public void detach(IComputerAccess computer) {
		if (computers.containsKey(computer.getID())) {
			computers.remove(computer.getID());
		}
	}
}
