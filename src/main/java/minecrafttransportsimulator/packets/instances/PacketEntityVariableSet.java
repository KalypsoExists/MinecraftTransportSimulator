package minecrafttransportsimulator.packets.instances;

import io.netty.buffer.ByteBuf;
import minecrafttransportsimulator.entities.components.AEntityC_Definable;
import minecrafttransportsimulator.mcinterface.WrapperWorld;
import minecrafttransportsimulator.packets.components.APacketEntity;

/**Packet used to set variable states.  Sent from clients to servers to
 * tell them to change the custom state of an entity variable, and then sent
 * back to all clients to have them update those states.  May also be sent directly
 * from a server to all clients if the server is the one that changed the state.
 * 
 * @author don_bruce
 */
public class PacketEntityVariableSet extends APacketEntity<AEntityC_Definable<?>>{
	private final String variableName;
	private final double variableValue;
	
	public PacketEntityVariableSet(AEntityC_Definable<?> entity, String variableName, double variableValue){
		super(entity);
		this.variableName = variableName;
		this.variableValue = variableValue;
	}
	
	public PacketEntityVariableSet(ByteBuf buf){
		super(buf);
		this.variableName = readStringFromBuffer(buf);
		this.variableValue = buf.readDouble();
	}
	
	@Override
	public void writeToBuffer(ByteBuf buf){
		super.writeToBuffer(buf);
		writeStringToBuffer(variableName, buf);
		buf.writeDouble(variableValue);
	}
	
	@Override
	public boolean handle(WrapperWorld world, AEntityC_Definable<?> entity){
		entity.setVariable(variableName, variableValue);
		return true;
	}
}
