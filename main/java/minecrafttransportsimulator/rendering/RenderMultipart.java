package minecrafttransportsimulator.rendering;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.lwjgl.opengl.GL11;

import minecrafttransportsimulator.MTS;
import minecrafttransportsimulator.baseclasses.MTSAxisAlignedBB;
import minecrafttransportsimulator.dataclasses.MTSInstruments.Controls;
import minecrafttransportsimulator.dataclasses.MTSInstruments.Instruments;
import minecrafttransportsimulator.dataclasses.MTSPackObject.PackControl;
import minecrafttransportsimulator.dataclasses.MTSPackObject.PackDisplayText;
import minecrafttransportsimulator.dataclasses.MTSPackObject.PackFileDefinitions;
import minecrafttransportsimulator.dataclasses.MTSPackObject.PackInstrument;
import minecrafttransportsimulator.dataclasses.MTSPackObject.PackPart;
import minecrafttransportsimulator.dataclasses.MTSPackObject.PackRotatableModelObject;
import minecrafttransportsimulator.entities.core.EntityMultipartChild;
import minecrafttransportsimulator.entities.core.EntityMultipartMoving;
import minecrafttransportsimulator.entities.core.EntityMultipartVehicle;
import minecrafttransportsimulator.entities.core.EntityMultipartVehicle.LightTypes;
import minecrafttransportsimulator.entities.main.EntityPlane;
import minecrafttransportsimulator.entities.parts.EntityEngineCar;
import minecrafttransportsimulator.entities.parts.EntitySeat;
import minecrafttransportsimulator.systems.ClientEventSystem;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.systems.OBJParserSystem;
import minecrafttransportsimulator.systems.PackParserSystem;
import minecrafttransportsimulator.systems.RotationSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.RenderWorldLastEvent;

/**Main render class for all multipart entities.
 * Renders the parent model, and all child models that have been registered by
 * {@link registerChildRender}.  Ensures all parts are rendered in the exact
 * location they should be in as all rendering is done in the same operation.
 * Entities don't render above 255 well due to the new chunk visibility system.
 * This code is present to be called manually from
 * {@link ClientEventSystem#on(RenderWorldLastEvent)}.
 *
 * @author don_bruce
 */
public final class RenderMultipart extends Render<EntityMultipartMoving>{
	private static final Minecraft minecraft = Minecraft.getMinecraft();
	/**Display list GL integers.  Keyed by model name.*/
	private static final Map<String, Integer> displayLists = new HashMap<String, Integer>();
	/**Rotatable parts for models.  Keyed by model name.*/
	private static final Map<String, List<RotatablePart>> rotatableLists = new HashMap<String, List<RotatablePart>>();
	/**Window parts for models.  Keyed by model name.*/
	private static final Map<String, List<WindowPart>> windowLists = new HashMap<String, List<WindowPart>>();
	/**Lights for models.  Keyed by model name.*/
	private static final Map<String, List<LightPart>> lightLists = new HashMap<String, List<LightPart>>();
	/**Model texture name.  Keyed by model name.*/
	private static final Map<String, ResourceLocation> textureMap = new HashMap<String, ResourceLocation>();
	private static final Map<EntityMultipartMoving, Byte> lastRenderPass = new HashMap<EntityMultipartMoving, Byte>();
	private static final Map<EntityMultipartMoving, Long> lastRenderTick = new HashMap<EntityMultipartMoving, Long>();
	private static final Map<EntityMultipartMoving, Float> lastRenderPartial = new HashMap<EntityMultipartMoving, Float>();
	private static final ResourceLocation vanillaGlassTexture = new ResourceLocation("minecraft", "textures/blocks/glass.png");
	private static final ResourceLocation lensFlareTexture = new ResourceLocation(MTS.MODID, "textures/rendering/lensflare.png");
	private static final ResourceLocation lightTexture = new ResourceLocation(MTS.MODID, "textures/rendering/light.png");
	private static final ResourceLocation lightBeamTexture = new ResourceLocation(MTS.MODID, "textures/rendering/lightbeam.png");
	
	public RenderMultipart(RenderManager renderManager){
		super(renderManager);
		RenderMultipartChild.init();
	}

	@Override
	protected ResourceLocation getEntityTexture(EntityMultipartMoving entity){
		return null;
	}
	
	@Override
	public void doRender(EntityMultipartMoving entity, double x, double y, double z, float entityYaw, float partialTicks){
		boolean didRender = false;
		if(entity.pack != null){ 
			if(lastRenderPass.containsKey(entity)){
				//Did we render this tick?
				if(lastRenderTick.get(entity) == entity.worldObj.getTotalWorldTime() && lastRenderPartial.get(entity) == partialTicks){
					//If we rendered last on a pass of 0 or 1 this tick, don't re-render some things.
					if(lastRenderPass.get(entity) != -1 && MinecraftForgeClient.getRenderPass() == -1){
						render(entity, Minecraft.getMinecraft().thePlayer, partialTicks, true);
						didRender = true;
					}
				}
			}
			if(!didRender){
				render(entity, Minecraft.getMinecraft().thePlayer, partialTicks, false);
			}
			lastRenderPass.put(entity, (byte) MinecraftForgeClient.getRenderPass());
			lastRenderTick.put(entity, entity.worldObj.getTotalWorldTime());
			lastRenderPartial.put(entity, partialTicks);
		}
	}
	
	public static void resetRenders(){
		for(Integer displayList : displayLists.values()){
			GL11.glDeleteLists(displayList, 1);
		}
		displayLists.clear();
		rotatableLists.clear();
		windowLists.clear();
		lightLists.clear();
	}
	
	public static boolean doesMultipartHaveLight(EntityMultipartMoving mover, LightTypes light){
		for(LightPart lightPart : lightLists.get(mover.pack.rendering.modelName)){
			if(lightPart.type.equals(light)){
				return true;
			}
		}
		return false;
	}
	
	private static void render(EntityMultipartMoving mover, EntityPlayer playerRendering, float partialTicks, boolean wasRenderedPrior){
		//Calculate various things.
		Entity renderViewEntity = minecraft.getRenderViewEntity();
		double playerX = renderViewEntity.lastTickPosX + (renderViewEntity.posX - renderViewEntity.lastTickPosX) * (double)partialTicks;
		double playerY = renderViewEntity.lastTickPosY + (renderViewEntity.posY - renderViewEntity.lastTickPosY) * (double)partialTicks;
		double playerZ = renderViewEntity.lastTickPosZ + (renderViewEntity.posZ - renderViewEntity.lastTickPosZ) * (double)partialTicks;
        
        
        double thisX = mover.lastTickPosX + (mover.posX - mover.lastTickPosX) * (double)partialTicks;
        double thisY = mover.lastTickPosY + (mover.posY - mover.lastTickPosY) * (double)partialTicks;
        double thisZ = mover.lastTickPosZ + (mover.posZ - mover.lastTickPosZ) * (double)partialTicks;
        double rotateYaw = -mover.rotationYaw + (mover.rotationYaw - mover.prevRotationYaw)*(double)(1 - partialTicks);
        double rotatePitch = mover.rotationPitch - (mover.rotationPitch - mover.prevRotationPitch)*(double)(1 - partialTicks);
        double rotateRoll = mover.rotationRoll - (mover.rotationRoll - mover.prevRotationRoll)*(double)(1 - partialTicks);

        //Set up position and lighting.
        GL11.glPushMatrix();
        GL11.glTranslated(thisX - playerX, thisY - playerY, thisZ - playerZ);
        int lightVar = mover.getBrightnessForRender(partialTicks);
        minecraft.entityRenderer.enableLightmap();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightVar%65536, lightVar/65536);
        RenderHelper.enableStandardItemLighting();
        
		//Bind texture.  Adds new element to cache if needed.
		PackFileDefinitions definition = PackParserSystem.getDefinitionForPack(mover.name);
		if(!textureMap.containsKey(definition.modelTexture)){
			if(definition.modelTexture.contains(":")){
				textureMap.put(mover.name, new ResourceLocation(definition.modelTexture));
			}else{
				textureMap.put(mover.name, new ResourceLocation(MTS.MODID, "textures/models/" + definition.modelTexture));
			}
		}
		minecraft.getTextureManager().bindTexture(textureMap.get(mover.name));
		//Render all the model parts except windows.
		//Those need to be rendered after the player if the player is rendered manually.
		if(MinecraftForgeClient.getRenderPass() != 1 && !wasRenderedPrior){
			GL11.glPushMatrix();
			GL11.glRotated(rotateYaw, 0, 1, 0);
	        GL11.glRotated(rotatePitch, 1, 0, 0);
	        GL11.glRotated(rotateRoll, 0, 0, 1);
			renderMainModel(mover);
			renderChildren(mover, partialTicks);
			GL11.glEnable(GL11.GL_NORMALIZE);
			renderWindows(mover);
			renderTextMarkings(mover);
			if(mover instanceof EntityMultipartVehicle){
				renderInstrumentsAndControls((EntityMultipartVehicle) mover);
			}
			GL11.glDisable(GL11.GL_NORMALIZE);
			if(Minecraft.getMinecraft().getRenderManager().isDebugBoundingBox()){
				renderBoundingBoxes(mover);
			}
			GL11.glPopMatrix();
		}
		
		//Check to see if we need to manually render riders.
		//MC culls rendering above build height depending on the direction the player is looking.
 		//Due to inconsistent culling based on view angle, this can lead to double-renders.
 		//Better than not rendering at all I suppose.
		if(MinecraftForgeClient.getRenderPass() != 1 && !wasRenderedPrior){
			 for(EntityMultipartChild child : mover.getChildren()){
				 if(child instanceof EntitySeat){
			         Entity rider = ((EntitySeat) child).getPassenger();
			         if(rider != null && !(minecraft.thePlayer.equals(rider) && minecraft.gameSettings.thirdPersonView == 0) && rider.posY > rider.worldObj.getHeight()){
			        	 GL11.glPushMatrix();
			        	 GL11.glTranslated(rider.posX - mover.posX, rider.posY - mover.posY, rider.posZ - mover.posZ);
			        	 Minecraft.getMinecraft().getRenderManager().renderEntityStatic(rider, partialTicks, false);
			        	 GL11.glPopMatrix();
			         }
				 }
		     }
		}
		
		//Lights and beacons get rendered in two passes.
		//The first renders the cases and bulbs, the second renders the beams and effects.
		//Make sure the light list is populated here before we try to render this, as loading de-syncs can leave it null.
		if(mover instanceof EntityMultipartVehicle && lightLists.get(mover.pack.rendering.modelName) != null){
			EntityMultipartVehicle vehicle = (EntityMultipartVehicle) mover;
			float sunLight = vehicle.worldObj.getSunBrightness(0)*vehicle.worldObj.getLightBrightness(vehicle.getPosition());
			float blockLight = vehicle.worldObj.getLightFromNeighborsFor(EnumSkyBlock.BLOCK, vehicle.getPosition())/15F;
			float electricFactor = (float) Math.min(vehicle.electricPower > 2 ? (vehicle.electricPower-2)/6F : 0, 1);
			float lightBrightness = (float) Math.min((1 - Math.max(sunLight, blockLight))*electricFactor, 1);

			GL11.glPushMatrix();
			GL11.glEnable(GL11.GL_NORMALIZE);
			GL11.glRotated(rotateYaw, 0, 1, 0);
	        GL11.glRotated(rotatePitch, 1, 0, 0);
	        GL11.glRotated(rotateRoll, 0, 0, 1);
	        renderLights(vehicle, sunLight, blockLight, lightBrightness, electricFactor, wasRenderedPrior);
			GL11.glDisable(GL11.GL_NORMALIZE);
			GL11.glPopMatrix();
			
			//Return all states to normal.
			minecraft.entityRenderer.enableLightmap();
			GL11.glEnable(GL11.GL_LIGHTING);
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			GL11.glDepthMask(true);
			GL11.glColor4f(1, 1, 1, 1);
		}
		
		//Render holograms for missing parts if applicable.
		if(MinecraftForgeClient.getRenderPass() != 0){
			renderPartBoxes(mover);
		}
		
		//Make sure lightmaps are set correctly.
		if(MinecraftForgeClient.getRenderPass() == -1){
			RenderHelper.disableStandardItemLighting();
			minecraft.entityRenderer.disableLightmap();
		}else{
			RenderHelper.enableStandardItemLighting();
			minecraft.entityRenderer.enableLightmap();
		}
		 GL11.glPopMatrix();
	}
	
	private static void renderMainModel(EntityMultipartMoving mover){
		GL11.glPushMatrix();
		//Normally we use the pack name, but since all displaylists
		//are the same for all models, this is more appropriate.
		if(displayLists.containsKey(mover.pack.rendering.modelName)){
			GL11.glCallList(displayLists.get(mover.pack.rendering.modelName));
			
			//The display list only renders static parts.  We need to render dynamic ones manually.
			//If this is a window, don't render it as that gets done all at once later.
			for(RotatablePart rotatable : rotatableLists.get(mover.pack.rendering.modelName)){
				if(!rotatable.name.contains("window")){
					GL11.glPushMatrix();
					rotateObject(mover, rotatable);
					GL11.glBegin(GL11.GL_TRIANGLES);
					for(Float[] vertex : rotatable.vertices){
						GL11.glTexCoord2f(vertex[3], vertex[4]);
						GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
						GL11.glVertex3f(vertex[0], vertex[1], vertex[2]);
					}
					GL11.glEnd();
					GL11.glPopMatrix();
				}
			}
		}else{
			Map<String, Float[][]> parsedModel = OBJParserSystem.parseOBJModel(new ResourceLocation(MTS.MODID, "objmodels/" + PackParserSystem.getPack(mover.name).rendering.modelName));
			int displayListIndex = GL11.glGenLists(1);
			GL11.glNewList(displayListIndex, GL11.GL_COMPILE);
			GL11.glBegin(GL11.GL_TRIANGLES);
			List<RotatablePart> rotatableParts = new ArrayList<RotatablePart>();
			List<WindowPart> windows = new ArrayList<WindowPart>();
			List<LightPart> lightParts = new ArrayList<LightPart>();
			for(Entry<String, Float[][]> entry : parsedModel.entrySet()){
				//Don't add rotatable model parts or windows to the display list.
				//Those go in separate maps, with windows going into both a rotatable and window mapping.
				//Do add lights, as they will be rendered both as part of the model and with special things.
				boolean shouldShapeBeInDL = true;
				if(entry.getKey().contains("$")){
					rotatableParts.add(new RotatablePart(mover, entry.getKey(), entry.getValue()));
					shouldShapeBeInDL = false;
				}
				if(entry.getKey().contains("&")){
					lightParts.add(new LightPart(entry.getKey(), entry.getValue()));
				}
				if(entry.getKey().toLowerCase().contains("window")){
					windows.add(new WindowPart(entry.getKey(), entry.getValue()));
					shouldShapeBeInDL = false;
				}
				if(shouldShapeBeInDL){
					for(Float[] vertex : entry.getValue()){
						GL11.glTexCoord2f(vertex[3], vertex[4]);
						GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
						GL11.glVertex3f(vertex[0], vertex[1], vertex[2]);
					}
				}
			}
			
			//Now finalize the maps.
			rotatableLists.put(mover.pack.rendering.modelName, rotatableParts);
			windowLists.put(mover.pack.rendering.modelName, windows);
			lightLists.put(mover.pack.rendering.modelName, lightParts);
			GL11.glEnd();
			GL11.glEndList();
			displayLists.put(mover.pack.rendering.modelName, displayListIndex);
		}
		GL11.glPopMatrix();
	}
	
	private static void rotateObject(EntityMultipartMoving mover, RotatablePart rotatable){
		float rotation = getRotationAngleForVariable(mover, rotatable.rotationVariable);
		if(rotation != 0){
			GL11.glTranslated(rotatable.rotationPoint.xCoord, rotatable.rotationPoint.yCoord, rotatable.rotationPoint.zCoord);
			GL11.glRotated(rotation*rotatable.rotationMagnitude, rotatable.rotationAxis.xCoord, rotatable.rotationAxis.yCoord, rotatable.rotationAxis.zCoord);
			GL11.glTranslated(-rotatable.rotationPoint.xCoord, -rotatable.rotationPoint.yCoord, -rotatable.rotationPoint.zCoord);
		}
	}
	
	private static float getRotationAngleForVariable(EntityMultipartMoving mover, String variable){
		switch(variable){
			case("door"): return mover.parkingBrakeOn && mover.velocity == 0 && !mover.locked ? 60 : 0;
			case("throttle"): return ((EntityMultipartVehicle) mover).throttle/4F;
			case("brake"): return mover.brakeOn ? 30 : 0;
			case("p_brake"): return mover.parkingBrakeOn ? 30 : 0;
			case("gearshift"): return ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1) != null ? (((EntityEngineCar) ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1)).isAutomatic ? Math.min(1, ((EntityEngineCar) ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1)).getCurrentGear()) : ((EntityEngineCar) ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1)).getCurrentGear())*5 : 0;
			case("driveshaft"): return (float) (((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1) != null ? ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1).RPM/((EntityEngineCar) ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1)).getRatioForGear(((EntityEngineCar) ((EntityMultipartVehicle) mover).getEngineByNumber((byte) 1)).getCurrentGear()) : 0);
			case("steeringwheel"): return mover.getSteerAngle();
			
			case("aileron"): return ((EntityPlane) mover).aileronAngle/10F;
			case("elevator"): return ((EntityPlane) mover).elevatorAngle/10F;
			case("rudder"): return ((EntityPlane) mover).rudderAngle/10F;
			case("flap"): return ((EntityPlane) mover).flapAngle/10F;
			default: return 0;
		}
	}
	
	private static void renderChildren(EntityMultipartMoving mover, float partialTicks){
		for(EntityMultipartChild child : mover.getChildren()){
			GL11.glPushMatrix();
    		GL11.glTranslatef(child.offsetX, child.offsetY, child.offsetZ);
    		if(child.turnsWithSteer){
    			if(child.offsetZ >= 0){
    				GL11.glRotatef(mover.getSteerAngle(), 0, 1, 0);
    			}else{
    				GL11.glRotatef(-mover.getSteerAngle(), 0, 1, 0);
    			}
    		}
    		RenderMultipartChild.renderChildEntity(child, partialTicks);
			GL11.glPopMatrix();
        }
	}
	
	private static void renderWindows(EntityMultipartMoving mover){
		minecraft.getTextureManager().bindTexture(vanillaGlassTexture);
		//Iterate through all windows.
		for(byte i=0; i<windowLists.get(mover.pack.rendering.modelName).size(); ++i){
			if(i >= mover.brokenWindows){
				GL11.glPushMatrix();
				//This is a window or set of windows.  Like the model, it will be triangle-based.
				//However, windows may be rotatable.  Check this before continuing.
				WindowPart window = windowLists.get(mover.pack.rendering.modelName).get(i);
				for(RotatablePart rotatable : rotatableLists.get(mover.pack.rendering.modelName)){
					if(rotatable.name.equals(window.name)){
						rotateObject(mover, rotatable);
					}
				}
				//If this window is a quad, draw quads.  Otherwise draw tris.
				if(window.vertices.length == 4){
					GL11.glBegin(GL11.GL_QUADS);
				}else{
					GL11.glBegin(GL11.GL_TRIANGLES);
				}
				for(Float[] vertex : window.vertices){
					GL11.glTexCoord2f(vertex[3], vertex[4]);
					GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
					GL11.glVertex3f(vertex[0], vertex[1], vertex[2]);
				}
				if(ConfigSystem.getBooleanConfig("InnerWindows")){
					for(int j=window.vertices.length-1; j >= 0; --j){
						Float[] vertex = window.vertices[j];
						GL11.glTexCoord2f(vertex[3], vertex[4]);
						GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
						GL11.glVertex3f(vertex[0], vertex[1], vertex[2]);	
					}
				}
				GL11.glEnd();
				GL11.glPopMatrix();
			}
		}
	}
	
	private static void renderTextMarkings(EntityMultipartMoving mover){
		for(PackDisplayText text : mover.pack.rendering.textMarkings){
			GL11.glPushMatrix();
			GL11.glTranslatef(text.pos[0], text.pos[1], text.pos[2]);
			GL11.glScalef(1F/16F, 1F/16F, 1F/16F);
			GL11.glRotatef(text.rot[0], 1, 0, 0);
			GL11.glRotatef(text.rot[1] + 180, 0, 1, 0);
			GL11.glRotatef(text.rot[2] + 180, 0, 0, 1);
			GL11.glScalef(text.scale, text.scale, text.scale);
			RenderHelper.disableStandardItemLighting();
			minecraft.fontRendererObj.drawString(mover.displayText, -minecraft.fontRendererObj.getStringWidth(mover.displayText)/2, 0, Color.decode(text.color).getRGB());
			GL11.glPopMatrix();
		}
		GL11.glColor3f(1.0F, 1.0F, 1.0F);
		RenderHelper.enableStandardItemLighting();
	}
	
	private static void renderLights(EntityMultipartVehicle vehicle, float sunLight, float blockLight, float lightBrightness, float electricFactor, boolean wasRenderedPrior){
		for(LightPart light : lightLists.get(vehicle.pack.rendering.modelName)){
			boolean lightSwitchOn = vehicle.isLightOn(light.type);
			//Fun with bit shifting!  20 bits make up the light on index here, so align to a 20 tick cycle.
			boolean lightActuallyOn = lightSwitchOn && ((light.flashBits >> vehicle.ticksExisted%20) & 1) > 0;
			//Used to make the cases of the lights full brightness.  Used when lights are brighter than the surroundings.
			boolean overrideCaseBrightness = lightBrightness > Math.max(sunLight, blockLight) && lightActuallyOn;
			
			GL11.glPushMatrix();
			//This light may be rotatable.  Check this before continuing.
			for(RotatablePart rotatable : rotatableLists.get(vehicle.pack.rendering.modelName)){
				if(rotatable.name.equals(light.name)){
					rotateObject(vehicle, rotatable);
				}
			}
			
			if(MinecraftForgeClient.getRenderPass() != 1 && !wasRenderedPrior){
				GL11.glPushMatrix();
				if(overrideCaseBrightness){
					GL11.glDisable(GL11.GL_LIGHTING);
					minecraft.entityRenderer.disableLightmap();
				}else{
					GL11.glEnable(GL11.GL_LIGHTING);
					minecraft.entityRenderer.enableLightmap();
				}
				GL11.glDisable(GL11.GL_BLEND);
				
				//Cover rendering.
				minecraft.getTextureManager().bindTexture(vanillaGlassTexture);
				GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
				GL11.glBegin(GL11.GL_TRIANGLES);
				for(Float[] vertex : light.vertices){
					//Add a slight translation and scaling to the light coords based on the normals to make the lens cover.
					//Also modify the cover size to ensure the whole cover is a single glass square.
					GL11.glTexCoord2f(vertex[3], vertex[4]);
					GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
					GL11.glVertex3f(vertex[0]+vertex[5]*0.0003F, vertex[1]+vertex[6]*0.0003F, vertex[2]+vertex[7]*0.0003F);	
				}
				GL11.glEnd();
				
				//Light rendering.
				if(lightActuallyOn){
					GL11.glDisable(GL11.GL_LIGHTING);
					GL11.glEnable(GL11.GL_BLEND);
					minecraft.getTextureManager().bindTexture(lightTexture);
					GL11.glColor4f(light.color.getRed()/255F, light.color.getGreen()/255F, light.color.getBlue()/255F, electricFactor);
					GL11.glBegin(GL11.GL_TRIANGLES);
					for(Float[] vertex : light.vertices){
						//Add a slight translation and scaling to the light coords based on the normals to make the light.
						GL11.glTexCoord2f(vertex[3], vertex[4]);
						GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
						GL11.glVertex3f(vertex[0]+vertex[5]*0.0001F, vertex[1]+vertex[6]*0.0001F, vertex[2]+vertex[7]*0.0001F);	
					}
					GL11.glEnd();
					GL11.glDisable(GL11.GL_BLEND);
					GL11.glEnable(GL11.GL_LIGHTING);
				}
				GL11.glPopMatrix();
			}
			
			//Lens flare.
			if(lightActuallyOn && lightBrightness > 0 && MinecraftForgeClient.getRenderPass() != 0 && !wasRenderedPrior){
				for(byte i=0; i<light.centerPoints.length; ++i){
					GL11.glPushMatrix();
					GL11.glEnable(GL11.GL_BLEND);
					GL11.glDisable(GL11.GL_LIGHTING);
					minecraft.entityRenderer.disableLightmap();
					minecraft.getTextureManager().bindTexture(lensFlareTexture);
					GL11.glColor4f(light.color.getRed()/255F, light.color.getGreen()/255F, light.color.getBlue()/255F, lightBrightness);
					GL11.glBegin(GL11.GL_TRIANGLES);
					for(byte j=0; j<6; ++j){
						Float[] vertex = light.vertices[((short) i)*6+j];
						//Add a slight translation to the light size to make the flare move off it.
						//Then apply scaling factor to make the flare larger than the light.
						GL11.glTexCoord2f(vertex[3], vertex[4]);
						GL11.glNormal3f(vertex[5], vertex[6], vertex[7]);
						GL11.glVertex3d(vertex[0]+vertex[5]*0.0002F + (vertex[0] - light.centerPoints[i].xCoord)*(2 + light.size[i]*0.25F), 
								vertex[1]+vertex[6]*0.0002F + (vertex[1] - light.centerPoints[i].yCoord)*(2 + light.size[i]*0.25F), 
								vertex[2]+vertex[7]*0.0002F + (vertex[2] - light.centerPoints[i].zCoord)*(2 + light.size[i]*0.25F));	
					}
					GL11.glEnd();
					GL11.glPopMatrix();
				}
			}
			
			//Render beam if light has one.
			if(lightActuallyOn && lightBrightness > 0 && light.type.hasBeam && MinecraftForgeClient.getRenderPass() == -1){
				GL11.glPushMatrix();
		    	GL11.glDisable(GL11.GL_LIGHTING);
		    	GL11.glEnable(GL11.GL_BLEND);
		    	minecraft.entityRenderer.disableLightmap();
				minecraft.getTextureManager().bindTexture(lightBeamTexture);
		    	GL11.glColor4f(1, 1, 1, Math.min(vehicle.electricPower > 4 ? 1.0F : 0, lightBrightness/2F));
		    	//Allows making things brighter by using alpha blending.
		    	GL11.glDepthMask(false);
		    	GL11.glBlendFunc(GL11.GL_DST_COLOR, GL11.GL_SRC_ALPHA);
				
				//As we can have more than one light per definition, we will only render 6 vertices at a time.
				//Use the center point arrays for this; normals are the same for all 6 vertex sets so use whichever.
				for(byte i=0; i<light.centerPoints.length; ++i){
					GL11.glPushMatrix();
					GL11.glTranslated(light.centerPoints[i].xCoord - light.vertices[i*6][5]*0.15F, light.centerPoints[i].yCoord - light.vertices[i*6][6]*0.15F, light.centerPoints[i].zCoord - light.vertices[i*6][7]*0.15F);
					Vec3d endpointVec = new Vec3d(light.vertices[i*6][5]*light.size[i]*3F, light.vertices[i*6][6]*light.size[i]*3F, light.vertices[i*6][7]*light.size[i]*3F);
					//Now that we are at the starting location for the beam, rotate the matrix to get the correct direction.
					GL11.glDepthMask(false);
					for(byte j=0; j<=2; ++j){
			    		drawCone(endpointVec, light.size[i], false);
			    	}
					drawCone(endpointVec, light.size[i], true);
					GL11.glPopMatrix();
				}
		    	GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		    	GL11.glDepthMask(true);
				GL11.glDisable(GL11.GL_BLEND);
				GL11.glEnable(GL11.GL_LIGHTING);
				GL11.glPopMatrix();
			}
			
			GL11.glPopMatrix();
		}
	}
	
    private static void drawCone(Vec3d endPoint, double radius, boolean reverse){
		GL11.glBegin(GL11.GL_TRIANGLE_FAN);
		GL11.glTexCoord2f(0, 0);
		GL11.glVertex3d(0, 0, 0);
    	if(reverse){
    		for(float theta=0; theta < 2*Math.PI + 0.1; theta += 2F*Math.PI/40F){
    			GL11.glTexCoord2f(theta, 1);
    			GL11.glVertex3d(endPoint.xCoord + radius*Math.cos(theta), endPoint.yCoord + radius*Math.sin(theta), endPoint.zCoord);
    		}
    	}else{
    		for(float theta=(float) (2*Math.PI); theta>=0 - 0.1; theta -= 2F*Math.PI/40F){
    			GL11.glTexCoord2f(theta, 1);
    			GL11.glVertex3d(endPoint.xCoord + radius*Math.cos(theta), endPoint.yCoord + radius*Math.sin(theta), endPoint.zCoord);
    		}
    	}
    	GL11.glEnd();
    }
	
	private static void renderInstrumentsAndControls(EntityMultipartVehicle vehicle){
		GL11.glPushMatrix();
		GL11.glScalef(1F/16F/8F, 1F/16F/8F, 1F/16F/8F);
		for(byte i=0; i<vehicle.pack.motorized.instruments.size(); ++i){
			Instruments instrument = vehicle.getInstrumentNumber(i);
			PackInstrument packInstrument = vehicle.pack.motorized.instruments.get(i);
			if(instrument != null && packInstrument != null){
				GL11.glPushMatrix();
				GL11.glTranslatef(packInstrument.pos[0]*8, packInstrument.pos[1]*8, packInstrument.pos[2]*8);
				GL11.glRotatef(packInstrument.rot[0], 1, 0, 0);
				GL11.glRotatef(packInstrument.rot[1], 0, 1, 0);
				GL11.glRotatef(packInstrument.rot[2], 0, 0, 1);
				GL11.glScalef(packInstrument.scale, packInstrument.scale, packInstrument.scale);
				RenderInstruments.drawInstrument(vehicle, 0, 0, instrument, false, packInstrument.optionalEngineNumber);
				GL11.glPopMatrix();
			}
		}
		for(byte i=0; i<vehicle.pack.motorized.controls.size(); ++i){
			PackControl packControl = vehicle.pack.motorized.controls.get(i);
			GL11.glPushMatrix();
			GL11.glTranslatef(packControl.pos[0]*8, packControl.pos[1]*8, packControl.pos[2]*8);
			for(Controls control : Controls.values()){
				if(control.name().toLowerCase().equals(packControl.controlName)){
					RenderControls.drawControl(vehicle, control, false);
				}
			}
			GL11.glPopMatrix();
		}
		GL11.glPopMatrix();
	}
	
	private static void renderBoundingBoxes(EntityMultipartMoving mover){
		GL11.glPushMatrix();
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glColor3f(0.0F, 0.0F, 0.0F);
		GL11.glLineWidth(3.0F);
		for(MTSAxisAlignedBB box : mover.getCurrentCollisionBoxes()){
			//box = box.offset(-mover.posX, -mover.posY, -mover.posZ);
			GL11.glBegin(GL11.GL_LINES);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY - box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY - box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY - box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY - box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY + box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY + box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY + box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY + box.height/2F, box.relZ + box.width/2F);
			
			GL11.glVertex3d(box.relX - box.width/2F, box.relY - box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY - box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY - box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY - box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY + box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY + box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY + box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY + box.height/2F, box.relZ + box.width/2F);
			
			GL11.glVertex3d(box.relX - box.width/2F, box.relY - box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY + box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY - box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY + box.height/2F, box.relZ - box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY - box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX - box.width/2F, box.relY + box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY - box.height/2F, box.relZ + box.width/2F);
			GL11.glVertex3d(box.relX + box.width/2F, box.relY + box.height/2F, box.relZ + box.width/2F);
			GL11.glEnd();
		}
		GL11.glLineWidth(1.0F);
		GL11.glColor3f(1.0F, 1.0F, 1.0F);
		GL11.glEnable(GL11.GL_LIGHTING);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glPopMatrix();
	}
	
	private static void renderPartBoxes(EntityMultipartMoving mover){
		EntityPlayer player = minecraft.thePlayer;
		ItemStack heldStack = player.getHeldItemMainhand();
		if(heldStack != null){
			String partBoxToRender = heldStack.getItem().getRegistryName().getResourcePath();
			
			for(PackPart packPart : mover.pack.parts){
				boolean isPresent = false;
				boolean isHoldingPart = false;
				for(EntityMultipartChild child : mover.getChildren()){
					if(child.offsetX == packPart.pos[0] && child.offsetY == packPart.pos[1] && child.offsetZ == packPart.pos[2]){
						isPresent = true;
						break;
					}
				}
	
				for(String partName : packPart.names){
					if(partName.equals(partBoxToRender)){
						isHoldingPart = true;
						break;
					}
				}
						
				if(!isPresent && isHoldingPart){
					Vec3d offset = RotationSystem.getRotatedPoint(packPart.pos[0], packPart.pos[1], packPart.pos[2], mover.rotationPitch, mover.rotationYaw, mover.rotationRoll);
					AxisAlignedBB box = new AxisAlignedBB((float) (offset.xCoord) - 0.75F, (float) (offset.yCoord) - 0.75F, (float) (offset.zCoord) - 0.75F, (float) (offset.xCoord) + 0.75F, (float) (offset.yCoord) + 1.25F, (float) (offset.zCoord) + 0.75F);
					
					GL11.glPushMatrix();
					GL11.glDisable(GL11.GL_TEXTURE_2D);
					GL11.glDisable(GL11.GL_LIGHTING);
					GL11.glEnable(GL11.GL_BLEND);
					GL11.glColor4f(0, 1, 0, 0.25F);
					GL11.glBegin(GL11.GL_QUADS);
					
					GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
					GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
					GL11.glVertex3d(box.minX, box.minY, box.maxZ);
					GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
					
					GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
					GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
					GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
					GL11.glVertex3d(box.maxX, box.minY, box.minZ);
					
					GL11.glVertex3d(box.maxX, box.minY, box.minZ);
					GL11.glVertex3d(box.minX, box.minY, box.minZ);
					GL11.glVertex3d(box.minX, box.maxY, box.minZ);
					GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
					
					GL11.glVertex3d(box.minX, box.minY, box.minZ);
					GL11.glVertex3d(box.minX, box.minY, box.maxZ);
					GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
					GL11.glVertex3d(box.minX, box.maxY, box.minZ);
					
					GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
					GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
					GL11.glVertex3d(box.minX, box.maxY, box.minZ);
					GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
					
					GL11.glVertex3d(box.minX, box.minY, box.maxZ);
					GL11.glVertex3d(box.minX, box.minY, box.minZ);
					GL11.glVertex3d(box.maxX, box.minY, box.minZ);
					GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
					GL11.glEnd();

					GL11.glColor4f(1, 1, 1, 1);
					GL11.glDisable(GL11.GL_BLEND);
					GL11.glEnable(GL11.GL_LIGHTING);
					GL11.glEnable(GL11.GL_TEXTURE_2D);
					GL11.glPopMatrix();
				}
			}
		}
	}
	
	private static final class RotatablePart{
		private final String name;
		private final Float[][] vertices;
		
		private final Vec3d rotationPoint;
		private final Vec3d rotationAxis;
		private final float rotationMagnitude;
		private final String rotationVariable;
		
		private RotatablePart(EntityMultipartMoving mover, String name, Float[][] vertices){
			this.name = name.toLowerCase();
			this.vertices = vertices;
			this.rotationPoint = getRotationPoint(mover, name);
			Vec3d rotationAxisTemp = getRotationAxis(mover, name);
			this.rotationAxis = rotationAxisTemp.normalize();
			this.rotationMagnitude = (float) rotationAxisTemp.lengthVector();
			this.rotationVariable = getRotationVariable(mover, name);
		}
		
		private static Vec3d getRotationPoint(EntityMultipartMoving mover, String name){
			for(PackRotatableModelObject rotatable : mover.pack.rendering.rotatableModelObjects){
				if(rotatable.partName.equals(name)){
					if(rotatable.rotationPoint != null){
						return new Vec3d(rotatable.rotationPoint[0], rotatable.rotationPoint[1], rotatable.rotationPoint[2]);
					}
				}
			}
			return Vec3d.ZERO;
		}
		
		private static Vec3d getRotationAxis(EntityMultipartMoving mover, String name){
			for(PackRotatableModelObject rotatable : mover.pack.rendering.rotatableModelObjects){
				if(rotatable.partName.equals(name)){
					if(rotatable.rotationAxis != null){
						return new Vec3d(rotatable.rotationAxis[0], rotatable.rotationAxis[1], rotatable.rotationAxis[2]);
					}
				}
			}
			return Vec3d.ZERO;
		}
		
		private static String getRotationVariable(EntityMultipartMoving mover, String name){
			for(PackRotatableModelObject rotatable : mover.pack.rendering.rotatableModelObjects){
				if(rotatable.partName.equals(name)){
					if(rotatable.partName != null){
						return rotatable.rotationVariable.toLowerCase();
					}
				}
			}
			return "";
		}
	}
	
	private static final class WindowPart{
		private final String name;
		private final Float[][] vertices;
		
		private WindowPart(String name, Float[][] vertices){
			this.name = name.toLowerCase();
			this.vertices = vertices;
		}
	}
	
	private static final class LightPart{
		private final String name;
		private final LightTypes type;
		private final Float[][] vertices;
		private final Vec3d[] centerPoints;
		private final Float[] size;
		private final Color color;
		private final int flashBits;
		
		private LightPart(String name, Float[][] masterVertices){
			this.name = name.toLowerCase();
			this.type = getTypeFromName(name);
			this.vertices = new Float[masterVertices.length][];
			this.centerPoints = new Vec3d[masterVertices.length/6];
			this.size = new Float[masterVertices.length/6];
			
			for(byte i=0; i<centerPoints.length; ++i){
				double minX = 999;
				double maxX = -999;
				double minY = 999;
				double maxY = -999;
				double minZ = 999;
				double maxZ = -999;
				for(byte j=0; j<6; ++j){
					Float[] masterVertex = masterVertices[((short) i)*6 + j];
					minX = Math.min(masterVertex[0], minX);
					maxX = Math.max(masterVertex[0], maxX);
					minY = Math.min(masterVertex[1], minY);
					maxY = Math.max(masterVertex[1], maxY);
					minZ = Math.min(masterVertex[2], minZ);
					maxZ = Math.max(masterVertex[2], maxZ);
					
					Float[] newVertex = new Float[masterVertex.length];
					newVertex[0] = masterVertex[0];
					newVertex[1] = masterVertex[1];
					newVertex[2] = masterVertex[2];
					//Adjust UV point here to change this to glass coords.
					switch(j){
						case(0): newVertex[3] = 0.0F; newVertex[4] = 0.0F; break;
						case(1): newVertex[3] = 0.0F; newVertex[4] = 1.0F; break;
						case(2): newVertex[3] = 1.0F; newVertex[4] = 1.0F; break;
						case(3): newVertex[3] = 1.0F; newVertex[4] = 1.0F; break;
						case(4): newVertex[3] = 1.0F; newVertex[4] = 0.0F; break;
						case(5): newVertex[3] = 0.0F; newVertex[4] = 0.0F; break;
					}
					newVertex[5] = masterVertex[5];
					newVertex[6] = masterVertex[6];
					newVertex[7] = masterVertex[7];
					
					this.vertices[((short) i)*6 + j] = newVertex;
				}
				centerPoints[i] = new Vec3d(minX + (maxX - minX)/2D, minY + (maxY - minY)/2D, minZ + (maxZ - minZ)/2D);
				size[i] = (float) Math.max(Math.max(maxX - minX, maxZ - minZ), maxY - minY)*16F;
			}
			//Lights are in the format of "&NAME_XXXXXX_YYYYY"
			//Where NAME is what switch it goes to, XXXXXX is the color, and YYYYY is the blink rate. 
			this.color = Color.decode("0x" + name.substring(name.indexOf('_') + 1, name.indexOf('_') + 7));
			this.flashBits = Integer.decode("0x" + name.substring(name.lastIndexOf('_') + 1));
		}
		
		private LightTypes getTypeFromName(String lightName){
			for(LightTypes light : LightTypes.values()){
				if(lightName.toLowerCase().contains(light.name().toLowerCase())){
					return light;
				}
			}
			return null;
		}
	}
}
