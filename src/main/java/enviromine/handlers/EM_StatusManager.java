package enviromine.handlers;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import com.google.common.base.Stopwatch;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.common.registry.EntityRegistry;
import enviromine.EnviroPotion;
import enviromine.core.EM_Settings;
import enviromine.core.EnviroMine;
import enviromine.gui.EM_GuiEnviroMeters;
import enviromine.trackers.ArmorProperties;
import enviromine.trackers.BlockProperties;
import enviromine.trackers.EntityProperties;
import enviromine.trackers.EnviroDataTracker;
import enviromine.trackers.ItemProperties;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockLeavesBase;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.packet.Packet250CustomPayload;
import net.minecraft.potion.Potion;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.FoodStats;
import net.minecraft.util.MathHelper;
import net.minecraft.village.Village;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeDictionary.Type;
import net.minecraftforge.common.EnumPlantType;

public class EM_StatusManager
{
	public static HashMap<String,EnviroDataTracker> trackerList = new HashMap<String,EnviroDataTracker>();
	
	public static void addToManager(EnviroDataTracker tracker)
	{
		if(tracker.trackedEntity instanceof EntityPlayer)
		{
			trackerList.put("" + tracker.trackedEntity.getEntityName(), tracker);
		} else
		{
			trackerList.put("" + tracker.trackedEntity.entityId, tracker);
		}
	}
	
	public static void updateTracker(EnviroDataTracker tracker)
	{
		if(tracker == null)
		{
			return;
		}
		
		if(EnviroMine.proxy.isClient() && Minecraft.getMinecraft().isIntegratedServerRunning())
		{
			if(Minecraft.getMinecraft().getIntegratedServer().getServerListeningThread().isGamePaused() && !EnviroMine.proxy.isOpenToLAN())
			{
				return;
			}
		}
		
		tracker.updateTimer += 1;
		
		if(tracker.updateTimer >= 30)
		{
			tracker.updateData();
			
			if(!EnviroMine.proxy.isClient() || EnviroMine.proxy.isOpenToLAN())
			{
				syncMultiplayerTracker(tracker);
			}
		}
	}
	
	public static void syncMultiplayerTracker(EnviroDataTracker tracker)
	{
		tracker.fixFloatinfPointErrors();
		String dataString = "";
		if(tracker.trackedEntity instanceof EntityPlayer)
		{
			dataString = ("ID:0," + ((EntityPlayer)tracker.trackedEntity).username + "," + tracker.airQuality + "," + tracker.bodyTemp + "," + tracker.hydration + "," + tracker.sanity + "," + tracker.airTemp);
		} else
		{
			return;
			//dataString = ("ID:0," + tracker.trackedEntity.entityId + "," + tracker.airQuality + "," + tracker.bodyTemp + "," + tracker.hydration + "," + tracker.sanity);
		}
		
		if(dataString.length() >= 2048)
		{
			EnviroMine.logger.log(Level.WARNING, "Tracker Sync data too long! Problems may occur client side while parsing!");
		}
		
		try
		{
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			DataOutputStream outputStream = new DataOutputStream(bos);
			
			outputStream.writeBytes(dataString);
			
			Packet250CustomPayload packet = new Packet250CustomPayload();
			packet.channel = EM_Settings.Channel;
			packet.data = bos.toByteArray();
			packet.length = bos.size();
			PacketDispatcher.sendPacketToAllAround(tracker.trackedEntity.posX, tracker.trackedEntity.posY, tracker.trackedEntity.posZ, 128, tracker.trackedEntity.dimension, packet);
			
			outputStream.close();
			bos.close();
		} catch (IOException e)
		{
			EnviroMine.logger.log(Level.SEVERE, "EnviroMine failed to build tracker sync packet!", e);
		}
		
		
	}
	
	public static EnviroDataTracker lookupTracker(EntityLivingBase entity)
	{
		if(entity instanceof EntityPlayer)
		{
			if(trackerList.containsKey("" + entity.getEntityName()))
			{
				return trackerList.get("" + entity.getEntityName());
			} else
			{
				return null;
			}
		} else
		{
			if(trackerList.containsKey("" + entity.entityId))
			{
				return trackerList.get("" + entity.entityId);
			} else
			{
				return null;
			}
		}
	}
	
	public static EnviroDataTracker lookupTrackerFromUsername(String username)
	{
		return trackerList.get(username);
	}
	
	private static Stopwatch timer = new Stopwatch();
	
	public static float[] getSurroundingData(EntityLivingBase entityLiving, int range)
	{
		if(EnviroMine.proxy.isClient() && entityLiving.getEntityName().equals(Minecraft.getMinecraft().thePlayer.getEntityName()) && !timer.isRunning())
		{
			timer.start();
		}
		
		float[] data = new float[8];
		
		float sanityRate = -0.005F;
		float sanityStartRate = sanityRate;
		
		float quality = 0;
		double leaves = 0;
		float sBoost = 0;
		
		float dropSpeed = 0.001F;
		float riseSpeed = 0.001F;
		
		float temp = -999F;
		float cooling = 0;
		float dehydrateBonus = 0.0F;
		int animalHostility = 0;
		boolean nearLava = false;
		float dist = 0;
		float solidBlocks = 0;
		
		int i = MathHelper.floor_double(entityLiving.posX);
		int j = MathHelper.floor_double(entityLiving.posY);
		int k = MathHelper.floor_double(entityLiving.posZ);
		
		if(entityLiving.worldObj == null)
		{
			return data;
		}
		
		Chunk chunk = entityLiving.worldObj.getChunkFromBlockCoords(i, k);
		
		if(chunk == null)
		{
			return data;
		}
		
		BiomeGenBase biome = chunk.getBiomeGenForWorldCoords(i & 15, k & 15, entityLiving.worldObj.getWorldChunkManager());
		ArrayList<Type> bTypeList = new ArrayList<Type>(Arrays.asList(BiomeDictionary.getTypesForBiome(biome)));
		
		if(biome == null)
		{
			return data;
		}
		
		float surBiomeTemps = 0;
		int biomeTempChecks = 0;
		
		boolean isDay = entityLiving.worldObj.isDaytime();
		
		if(entityLiving.worldObj.provider.hasNoSky)
		{
			isDay = false;
		}
		
		int lightLev = 0;
		int blockLightLev = 0;
		
		if(j > 0)
		{
			if(j >= 256)
			{
				lightLev = 15;
				blockLightLev = 15;
			} else
			{
				lightLev = chunk.getSavedLightValue(EnumSkyBlock.Sky, i & 0xf, j, k & 0xf);
				blockLightLev = chunk.getSavedLightValue(EnumSkyBlock.Block, i & 0xf, j, k & 0xf);
			}
		}
		
		if(!isDay && blockLightLev <= 1 && entityLiving.getActivePotionEffect(Potion.nightVision) != null)
		{
			sanityStartRate = -0.01F;
			sanityRate = -0.01F;
		}
		
		for(int x = -range; x <= range; x++)
		{
			for(int y = -range; y <= range; y++)
			{
				for(int z = -range; z <= range; z++)
				{
					
					if(y == 0)
					{
						Chunk testChunk = entityLiving.worldObj.getChunkFromBlockCoords((i + x), (k + z));
						BiomeGenBase testBiome = testChunk.getBiomeGenForWorldCoords((i + x) & 15, (k + z) & 15, entityLiving.worldObj.getWorldChunkManager());
						
						if(testBiome != null)
						{
							surBiomeTemps += testBiome.temperature;
							biomeTempChecks += 1;
						}
					}
					
					if(!EM_PhysManager.blockNotSolid(entityLiving.worldObj, x + i, y + j, z + k, false))
					{
						solidBlocks += 1;
					}
					
					dist = (float)entityLiving.getDistance(i + x, j + y, k + z);
						
					int id = 0;
					int meta = 0;
					
					// These need to be here to reset
					boolean isCustom = false;
					BlockProperties blockProps = null;
					
					id = entityLiving.worldObj.getBlockId(i + x, j + y, k + z);
					
					if(id != 0)
					{
						meta = entityLiving.worldObj.getBlockMetadata(i + x, j + y, k + z);
					}
					
					if(EM_Settings.blockProperties.containsKey("" + id + "," + meta) || EM_Settings.blockProperties.containsKey("" + id))
					{
						if(EM_Settings.blockProperties.containsKey("" + id + "," + meta))
						{
							blockProps = EM_Settings.blockProperties.get("" + id + "," + meta);
						} else
						{
							blockProps = EM_Settings.blockProperties.get("" + id);
						}
						
						if(blockProps.meta == meta || blockProps.meta == -1)
						{
							isCustom = true;
						}
						
					}
					
					if(isCustom && blockProps != null)
					{
						if(blockProps.air > 0F)
						{
							leaves += (blockProps.air/0.1F);
						} else if(quality >= blockProps.air && blockProps.air < 0 && quality <= 0)
						{
							quality = blockProps.air;
						}
						if(blockProps.enableTemp)
						{
							if(temp <= getTempFalloff(blockProps.temp, dist, range) && blockProps.temp > 0F)
							{
								temp = getTempFalloff(blockProps.temp, dist, range);
							} else if(blockProps.temp < 0F)
							{
								cooling += getTempFalloff(-blockProps.temp, dist, range);
							}
						}
						if(sanityRate >= blockProps.sanity && blockProps.sanity < 0 && sanityRate <= 0)
						{
							sanityRate = blockProps.sanity;
						} else if(sanityRate <= blockProps.sanity && blockProps.sanity > 0F)
						{
							if(Block.blocksList[blockProps.id] instanceof BlockFlower)
							{
								if(isDay)
								{
									if(sBoost < blockProps.sanity)
									{
										sBoost = blockProps.sanity;
									}
								}
							} else
							{
								if(sBoost < blockProps.sanity)
								{
									sBoost = blockProps.sanity;
								}
							}
						}
						
					} else if((id == Block.lavaMoving.blockID || id == Block.lavaStill.blockID))
					{
						if(quality > -1)
						{
							quality = -1;
						}
						if(temp < getTempFalloff(200, dist, range))
						{
							temp = getTempFalloff(200, dist, range);
						}
						nearLava = true;
					} else if(id == Block.fire.blockID)
					{
						if(quality > -0.5F)
						{
							quality = -0.5F;
						}
						if(temp < getTempFalloff(100, dist, range))
						{
							temp = getTempFalloff(100, dist, range);


						}
					} else if((id == Block.torchWood.blockID || id == Block.furnaceBurning.blockID))
					{
						if(quality > -0.25F)
						{
							quality = -0.25F;
						}
						if(temp < getTempFalloff(75, dist, range))
						{
							temp = getTempFalloff(75, dist, range);

						}
					} else if(Block.blocksList[id] instanceof BlockLeavesBase || Block.blocksList[id] instanceof BlockFlower || id == Block.waterlily.blockID || id == Block.grass.blockID)
					{
						boolean isPlant = true;
						
						if(Block.blocksList[id] instanceof BlockFlower & sBoost <= 0.1F)
						{
							if(((BlockFlower)Block.blocksList[id]).getPlantType(entityLiving.worldObj, i, j, k) == EnumPlantType.Plains)
							{
								if(isDay)
								{
									sBoost = 0.1F;
								}
								
								isPlant = false;
								leaves += 0.1;
							} else
							{
								isPlant = false;
							}
						}
						
						if(isPlant)
						{
							leaves += 1;
						}
					} else if(id == Block.netherrack.blockID)
					{
						if(temp < getTempFalloff(50, dist, range))
						{
							temp = getTempFalloff(50, dist, range);

						}
					} else if(id == Block.waterMoving.blockID || id == Block.waterStill.blockID || (id == Block.cauldron.blockID && meta > 0))
					{
						animalHostility = -1;
					} else if(id == Block.snow.blockID)
					{
						cooling += getTempFalloff(0.01F, dist, range);
					} else if(id == Block.blockSnow.blockID || id == Block.ice.blockID)
					{
						cooling += getTempFalloff(0.015F, dist, range);
					} else if(id == Block.flowerPot.blockID && (meta == 1 || meta == 2))
					{
						if(meta == 1 || meta == 2)
						{
							if(isDay)
							{
								if(sBoost < 0.1F)
								{
									sBoost = 0.1F;
								}
							}
							leaves += 1;
						} else if(meta != 0 && !(meta >= 7 && meta <= 10))
						{
							leaves += 1;
						}
					} else if(id == Block.skull.blockID)
					{
						if(sanityRate <= sanityStartRate && sanityRate > -0.1F)
						{
							sanityRate = -0.1F;
						}
					} else if(id == Block.slowSand.blockID)
					{
						if(sanityRate <= sanityStartRate && sanityRate > -0.05F)
						{
							sanityRate = -0.05F;
						}
					} else if(id == Block.web.blockID)
					{
						if(sanityRate <= sanityStartRate && sanityRate > -0.01F)
						{
							sanityRate = -0.01F;
						}
					} else if(id == Block.dragonEgg.blockID)
					{
						if(sBoost < 1F)
						{
							sBoost = 1F;
						}
					}
					
					if((id == Block.lavaMoving.blockID || id == Block.lavaStill.blockID))
					{
						nearLava = true;
					}
				}
			}
		}
		
		if(entityLiving instanceof EntityPlayer)
		{
			EntityPlayer player = (EntityPlayer)entityLiving;
			
			for(int slot = 0; slot < 9; slot++)
			{
				ItemStack stack = player.inventory.mainInventory[slot];
				
				if(stack == null)
				{
					continue;
				}
				
				float stackMult = 1F;
				
				if(stack.stackSize > 1)
				{
					stackMult = (stack.stackSize-1F)/63F + 1F;
				}
				
				if(EM_Settings.itemProperties.containsKey("" + stack.itemID + "," + stack.getItemDamage()) || EM_Settings.itemProperties.containsKey("" + stack.itemID))
				{
					ItemProperties itemProps;
					
					if(EM_Settings.itemProperties.containsKey("" + stack.itemID + "," + stack.getItemDamage()))
					{
						itemProps = EM_Settings.itemProperties.get("" + stack.itemID + "," + stack.getItemDamage());
					} else
					{
						itemProps = EM_Settings.itemProperties.get("" + stack.itemID);
					}
					
					if(itemProps.ambAir > 0F)
					{
						leaves += (itemProps.ambAir/0.1F) * stackMult;
					} else if(quality >= itemProps.ambAir * stackMult && itemProps.ambAir < 0 && quality <= 0)
					{
						quality = itemProps.ambAir * stackMult;
					}
					if(temp <= itemProps.ambTemp * stackMult && itemProps.enableTemp && itemProps.ambTemp > 0F)
					{
						temp = itemProps.ambTemp * stackMult;
					} else if(itemProps.enableTemp && itemProps.ambTemp < 0F)
					{
						cooling += -itemProps.ambTemp * stackMult;
					}
					if(sanityRate >= itemProps.ambSanity * stackMult && itemProps.ambSanity < 0 && sanityRate <= 0)
					{
						sanityRate = itemProps.ambSanity * stackMult;
					} else if(sBoost <= itemProps.ambSanity * stackMult && itemProps.ambSanity > 0F)
					{
						if(stack.getItem() instanceof ItemBlock)
						{
							if(Block.blocksList[((ItemBlock)stack.getItem()).getBlockID()] instanceof BlockFlower)
							{
								if(isDay)
								{
									sBoost = itemProps.ambSanity * stackMult;
								}
							} else
							{
								sBoost = itemProps.ambSanity * stackMult;
							}
						} else
						{
							sBoost = itemProps.ambSanity * stackMult;
						}
					}
				} else if(stack.getItem() instanceof ItemBlock)
				{
					ItemBlock itemBlock = (ItemBlock)stack.getItem();
					if(Block.blocksList[itemBlock.getBlockID()] instanceof BlockFlower && isDay & sBoost <= 0.1F)
					{
						if(((BlockFlower)Block.blocksList[itemBlock.getBlockID()]).getPlantType(entityLiving.worldObj, i, j, k) == EnumPlantType.Plains)
						{
							sBoost = 0.1F;
						}
					}
				}
			}
		}
		
		if(lightLev > 1 && !entityLiving.worldObj.provider.isHellWorld)
		{
			quality = 2F;
		} else
		{
			if(sanityRate <= sanityStartRate && sanityRate > -0.1F && ((blockLightLev <= 1 && entityLiving.getActivePotionEffect(Potion.nightVision) == null) || entityLiving.worldObj.provider.isHellWorld))
			{
				sanityRate = -0.1F;
			}
		}
		
		if(entityLiving.posY > 48 && !entityLiving.worldObj.provider.isHellWorld)
		{
			quality = 2F;
		}
		
		//float bTemp = biome.temperature * 2.25F;
		float bTemp = (surBiomeTemps / biomeTempChecks)* 2.25F;
		
		if(bTemp > 1.5F)
		{
			bTemp = 30F + ((bTemp - 1F) * 10);
		} else if(bTemp < -1.5F)
		{
			bTemp = -30F + ((bTemp + 1F) * 10);
		} else
		{
			bTemp *= 20;
		}
		
		if(!entityLiving.worldObj.provider.isHellWorld)
		{
			if(entityLiving.posY <= 48)
			{
				if(bTemp < 20F)
				{
					bTemp += (50 * (1 - (entityLiving.posY / 48)));
				} else
				{
					bTemp += (20 * (1 - (entityLiving.posY / 48)));
				}
			} else if(entityLiving.posY > 96 && entityLiving.posY < 256)
			{
				if(bTemp < 20F)
				{
					bTemp -= (float)(20F * ((entityLiving.posY - 96)/159));
				} else
				{
					bTemp -= (float)(40F * ((entityLiving.posY - 96)/159));
				}
			} else if(entityLiving.posY >= 256)
			{
				if(bTemp < 20F)
				{
					bTemp -= 20F;
				} else
				{
					bTemp -= 40F;
				}
			}
		}
		
		bTemp -= cooling;
		
		if(entityLiving instanceof EntityPlayer)
		{
			if(((EntityPlayer)entityLiving).isPlayerSleeping())
			{
				bTemp += 5F;
			}
		}
		
		if(entityLiving.worldObj.isRaining() && entityLiving.worldObj.canBlockSeeTheSky(i, j, k) && biome.rainfall != 0.0F)
		{
			bTemp -= 10F;
			dropSpeed = 0.005F;
			animalHostility = -1;
		}
		
		if(!entityLiving.worldObj.canBlockSeeTheSky(i, j, k) && isDay && !entityLiving.worldObj.isRaining())
		{
			bTemp -= 5F;
		}
		
		if(!isDay && bTemp > 0F)
		{
			if(biome.rainfall == 0.0F)
			{
				bTemp /= 9;
			} else
			{
				bTemp /= 2;
			}
		} else if(!isDay && bTemp <= 0F)
		{
			bTemp -= 10F;
		}
		
		if((entityLiving.worldObj.getBlockId(i, j, k) == Block.waterStill.blockID || entityLiving.worldObj.getBlockId(i, j, k) == Block.waterMoving.blockID) && entityLiving.posY > 48)
		{
			if(biome.getEnableSnow())
			{
				bTemp -= 10F;
				dropSpeed = 0.1F;
			} else
			{
				bTemp -= 5F;
				dropSpeed = 0.01F;
			}
		}
		
		List mobList = entityLiving.worldObj.getEntitiesWithinAABBExcludingEntity(entityLiving, AxisAlignedBB.getBoundingBox(entityLiving.posX - 2, entityLiving.posY - 2, entityLiving.posZ - 2, entityLiving.posX + 3, entityLiving.posY + 3, entityLiving.posZ + 3));
		
		Iterator iterator = mobList.iterator();
		
		float avgEntityTemp = 0.0F;
		int validEntities = 0;
		
		EnviroDataTracker tracker = lookupTracker(entityLiving);
		
		while(iterator.hasNext())
		{
			Entity mob = (Entity)iterator.next();
			
			if(!(mob instanceof EntityLivingBase))
			{
				continue;
			}
			
			EnviroDataTracker mobTrack = lookupTracker((EntityLivingBase)mob);
			EntityProperties livingProps = null;
			
			if(EntityList.getEntityID(mob) > 0)
			{
				if(EM_Settings.livingProperties.containsKey(EntityList.getEntityID(mob)))
				{
					livingProps = EM_Settings.livingProperties.get(EntityList.getEntityID(mob));
				}
			} else if(EntityRegistry.instance().lookupModSpawn(mob.getClass(), false) != null)
			{
				if(EM_Settings.livingProperties.containsKey(EntityRegistry.instance().lookupModSpawn(mob.getClass(), false).getModEntityId() + 128))
				{
					livingProps = EM_Settings.livingProperties.get(EntityRegistry.instance().lookupModSpawn(mob.getClass(), false).getModEntityId() + 128);
				}
			}
			
			if(mob instanceof EntityVillager && entityLiving instanceof EntityPlayer && entityLiving.canEntityBeSeen(mob) && EM_Settings.villageAssist)
			{
				EntityVillager villager = (EntityVillager)mob;
				Village village = entityLiving.worldObj.villageCollectionObj.findNearestVillage(MathHelper.floor_double(villager.posX), MathHelper.floor_double(villager.posY), MathHelper.floor_double(villager.posZ), 32);
				
				long assistTime = villager.getEntityData().getLong("Enviro_Assist_Time");
				long worldTime = entityLiving.worldObj.provider.getWorldTime();
				
				if(village != null && village.getReputationForPlayer(((EntityPlayer)entityLiving).username) >= 5 && !villager.isChild() && Math.abs(worldTime - assistTime) > 24000)
				{
					if(villager.getProfession() == 2) // Priest
					{
						if(sBoost < 1F)
						{
							sBoost = 1F;
						}
					} else if(villager.getProfession() == 0 && isDay) // Farmer
					{
						if(tracker.hydration < 50F)
						{
							tracker.hydration = 100F;
							
							if(tracker.bodyTemp >= 38F)
							{
								tracker.bodyTemp -= 1F;
							}
							entityLiving.worldObj.playSoundAtEntity(entityLiving, "random.drink", 1.0F, 1.0F);
							villager.playSound("mob.villager.yes", 1.0F, 1.0F);
							villager.getEntityData().setLong("Enviro_Assist_Time", worldTime);
						}
					} else if(villager.getProfession() == 4 && isDay) // Butcher
					{
						FoodStats food = ((EntityPlayer)entityLiving).getFoodStats();
						if(food.getFoodLevel() <= 10)
						{
							food.setFoodLevel(20);
							entityLiving.worldObj.playSoundAtEntity(entityLiving, "random.burp", 0.5F, entityLiving.worldObj.rand.nextFloat() * 0.1F + 0.9F);
							villager.playSound("mob.villager.yes", 1.0F, 1.0F);
							villager.getEntityData().setLong("Enviro_Assist_Time", worldTime);
						}
					}
				}
			}
			
			if(livingProps != null && entityLiving.canEntityBeSeen(mob))
			{
				if(sanityRate >= livingProps.ambSanity && livingProps.ambSanity < 0 && sanityRate <= 0)
				{
					sanityRate = livingProps.ambSanity;
				} else if(sanityRate <= livingProps.ambSanity && livingProps.ambSanity > 0F)
				{
					if(sBoost < livingProps.ambSanity)
					{
						sBoost = livingProps.ambSanity;
					}
				}
				
				if(livingProps.ambAir > 0F)
				{
					leaves += (livingProps.ambAir/0.1F);
				} else if(quality >= livingProps.ambAir && livingProps.ambAir < 0 && quality <= 0)
				{
					quality = livingProps.ambAir;
				}
				
				dehydrateBonus -= livingProps.ambHydration;
			} else if(mob instanceof EntityBat && entityLiving instanceof EntityPlayer && entityLiving.canEntityBeSeen(mob))
			{
				if(sanityRate <= sanityStartRate && sanityRate > -0.01F)
				{
					sanityRate = -0.01F;
				}
			} else if(mob.getEntityName().toLowerCase().contains("ender") && entityLiving instanceof EntityPlayer && entityLiving.canEntityBeSeen(mob))
			{
				if(sanityRate <= sanityStartRate && sanityRate > -0.1F)
				{
					sanityRate = -0.1F;
				}
			} else if(((EntityLivingBase)mob).getCreatureAttribute() == EnumCreatureAttribute.UNDEAD && entityLiving.canEntityBeSeen(mob))
			{
				if(sanityRate <= sanityStartRate && sanityRate > -0.01F)
				{
					sanityRate = -0.01F;
				}
			}
			
			if(mobTrack != null)
			{
				if(livingProps != null)
				{
					if(!livingProps.bodyTemp || !livingProps.shouldTrack)
					{
						avgEntityTemp += livingProps.ambTemp;
					} else
					{
						avgEntityTemp += mobTrack.bodyTemp;
					}
				} else
				{
					avgEntityTemp += mobTrack.bodyTemp;
				}
				validEntities += 1;
			} else
			{
				if(livingProps != null)
				{
					if(!livingProps.bodyTemp || !livingProps.shouldTrack)
					{
						avgEntityTemp += livingProps.ambTemp;
					} else
					{
						avgEntityTemp += 37F;
					}
					validEntities += 1;
				} else if(!(mob instanceof EntityMob))
				{
					avgEntityTemp += 37F;
					validEntities += 1;
				}
			}
		}
		
		if(validEntities > 0)
		{
			avgEntityTemp /= validEntities;
			
			if(bTemp < avgEntityTemp)
			{
				bTemp = (bTemp + avgEntityTemp) / 2;
			}
		}
		
		float fireProt = 0;
		
		{
			ItemStack helmet = entityLiving.getCurrentItemOrArmor(4);
			ItemStack plate = entityLiving.getCurrentItemOrArmor(3);
			ItemStack legs = entityLiving.getCurrentItemOrArmor(2);
			ItemStack boots = entityLiving.getCurrentItemOrArmor(1);
			
			float tempMultTotal = 0F;
			float addTemp = 0F;
			
			if(helmet != null)
			{
				NBTTagList enchTags = helmet.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.tagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.tagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.respiration.effectId)
						{
							leaves += 3F * enLV;
						} else if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(EM_Settings.armorProperties.containsKey(helmet.itemID))
				{
					ArmorProperties props = EM_Settings.armorProperties.get(helmet.itemID);
					
					if(isDay)
					{
						if(entityLiving.worldObj.canBlockSeeTheSky(i, j, k) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if(props.air > 0F)
					{
						leaves += (props.air/0.1F);
					} else if(quality >= props.air && props.air < 0 && quality <= 0)
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			if(plate != null)
			{
				NBTTagList enchTags = plate.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.tagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.tagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(EM_Settings.armorProperties.containsKey(plate.itemID))
				{
					ArmorProperties props = EM_Settings.armorProperties.get(plate.itemID);
					
					if(isDay)
					{
						if(entityLiving.worldObj.canBlockSeeTheSky(i, j, k) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if((quality <= props.air && props.air > 0F) || (quality >= props.air && props.air < 0 && quality <= 0))
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			if(legs != null)
			{
				NBTTagList enchTags = legs.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.tagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.tagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(EM_Settings.armorProperties.containsKey(legs.itemID))
				{
					ArmorProperties props = EM_Settings.armorProperties.get(legs.itemID);
					
					if(isDay)
					{
						if(entityLiving.worldObj.canBlockSeeTheSky(i, j, k) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if((quality <= props.air && props.air > 0F) || (quality >= props.air && props.air < 0 && quality <= 0))
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			if(boots != null)
			{
				NBTTagList enchTags = boots.getEnchantmentTagList();
				
				if(enchTags != null)
				{
					for(int index = 0; index < enchTags.tagCount(); index++)
					{
						int enID = ((NBTTagCompound)enchTags.tagAt(index)).getShort("id");
						int enLV = ((NBTTagCompound)enchTags.tagAt(index)).getShort("lvl");
						
						if(enID == Enchantment.fireProtection.effectId)
						{
							fireProt += enLV;
						}
					}
				}
				
				if(EM_Settings.armorProperties.containsKey(boots.itemID))
				{
					ArmorProperties props = EM_Settings.armorProperties.get(boots.itemID);
					
					if(isDay)
					{
						if(entityLiving.worldObj.canBlockSeeTheSky(i, j, k) && bTemp > 0F)
						{
							tempMultTotal += (props.sunMult - 1.0F);
							addTemp += props.sunTemp;
						} else
						{
							tempMultTotal += (props.shadeMult - 1.0F);
							addTemp += props.shadeTemp;
						}
					} else
					{
						tempMultTotal += (props.nightMult - 1.0F);
						addTemp += props.nightTemp;
					}
					
					if((quality <= props.air && props.air > 0F) || (quality >= props.air && props.air < 0 && quality <= 0))
					{
						quality = props.air;
					}
					
					if(sanityRate >= props.sanity && props.sanity < 0 && sanityRate <= 0)
					{
						sanityRate = props.sanity;
					} else if(sBoost <= props.sanity && props.sanity > 0F)
					{
						sBoost = props.sanity;
					}
				}
			}
			
			bTemp *= (1F + tempMultTotal);
			bTemp += addTemp;
			fireProt = 1F - fireProt/18F;
		}
		
		float tempFin = 0F;
		
		if(temp > bTemp)
		{
			tempFin = (bTemp + temp) / 2;
			if(temp > (bTemp + 5F))
			{
				riseSpeed = 0.005F;
			}
		} else
		{
			tempFin = bTemp;
		}
		
		if(entityLiving.getActivePotionEffect(Potion.hunger) != null)
		{
			dehydrateBonus += 0.1F;
		}
		
		if(bTypeList.contains(Type.NETHER) || nearLava || biome.rainfall == 0.0F)
		{
			riseSpeed = 0.005F;
			dehydrateBonus += 0.05F;
			if(animalHostility == 0)
			{
				animalHostility = 1;
			}
			
			if(bTypeList.contains(Type.NETHER) && quality <= -0.1F)
			{
				quality = -0.1F;
			}
		}
		
		if(biome.getIntRainfall() == 0 && isDay)
		{
			dehydrateBonus += 0.05F;
			if(animalHostility == 0)
			{
				animalHostility = 1;
			}
		}
		
		if(!entityLiving.isPotionActive(Potion.fireResistance))
		{
			if(entityLiving.worldObj.getBlockId(i, j, k) == Block.lavaStill.blockID || entityLiving.worldObj.getBlockId(i, j, k) == Block.lavaMoving.blockID)
			{
				tempFin = 200F;
				riseSpeed = 1.0F;
			} else if(entityLiving.isBurning())
			{
				if(tempFin <= 75F)
				{
					tempFin = 75F;
				}
				
				if(riseSpeed < 0.1F)
				{
					riseSpeed = 0.1F;
				}
			}
		}
		
		quality += (leaves * 0.1F);
		sanityRate += sBoost;
		
		if(quality < 0)
		{
			quality *= solidBlocks/Math.pow(range*2, 3);
		}
		
		if(entityLiving.isSprinting())
		{
			dehydrateBonus += 0.05F;
			if(riseSpeed < 0.01F)
			{
				riseSpeed = 0.01F;
			}
			tempFin += 2F;
		}
		
		data[0] = quality * (float)EM_Settings.airMult;
		data[1] = entityLiving.isPotionActive(Potion.fireResistance) && tempFin > 37F? 37F : (tempFin > 37F? 37F + ((tempFin-37F) * fireProt): tempFin);
		data[2] = nearLava? 1 : 0;
		data[3] = dehydrateBonus * (float)EM_Settings.hydrationMult;
		data[4] = dropSpeed * (float)EM_Settings.tempMult;
		data[5] = riseSpeed * (float)EM_Settings.tempMult * (tracker.bodyTemp < 37F? 1F : fireProt);
		data[6] = animalHostility;
		data[7] = sanityRate * (float)EM_Settings.sanityMult;
		
		if(EnviroMine.proxy.isClient() && entityLiving.getEntityName().equals(Minecraft.getMinecraft().thePlayer.getEntityName()) && timer.isRunning())
		{
			timer.stop();
			EM_GuiEnviroMeters.DB_timer = timer.toString();
			timer.reset();
		}
		return data;
	}
	
	public static float getBiomeTemprature(int x, int y, BiomeGenBase biome)
	{
		float temp = 0;
		
		return temp;
		
	}
	
	public static void removeTracker(EnviroDataTracker tracker)
	{
		if(trackerList.containsValue(tracker))
		{
			tracker.isDisabled = true;
			if(tracker.trackedEntity instanceof EntityPlayer)
			{
				trackerList.remove(((EntityPlayer)tracker.trackedEntity).username);
			} else
			{
				trackerList.remove("" + tracker.trackedEntity.entityId);
			}
		}
	}
	
	public static void saveAndRemoveTracker(EnviroDataTracker tracker)
	{
		if(trackerList.containsValue(tracker))
		{
			tracker.isDisabled = true;
			NBTTagCompound tags = tracker.trackedEntity.getEntityData();
			tags.setFloat("ENVIRO_AIR", tracker.airQuality);
			tags.setFloat("ENVIRO_HYD", tracker.hydration);
			tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
			tags.setFloat("ENVIRO_SAN", tracker.sanity);
			if(tracker.trackedEntity instanceof EntityPlayer)
			{
				trackerList.remove(((EntityPlayer)tracker.trackedEntity).username);
			} else
			{
				trackerList.remove("" + tracker.trackedEntity.entityId);
			}
		}
	}
	
	public static void saveTracker(EnviroDataTracker tracker)
	{
		NBTTagCompound tags = tracker.trackedEntity.getEntityData();
		tags.setFloat("ENVIRO_AIR", tracker.airQuality);
		tags.setFloat("ENVIRO_HYD", tracker.hydration);
		tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
		tags.setFloat("ENVIRO_SAN", tracker.sanity);
	}
	
	public static void removeAllTrackers()
	{
		Iterator<EnviroDataTracker> iterator = trackerList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			tracker.isDisabled = true;
		}
		
		trackerList.clear();
	}
	
	public static void saveAndDeleteAllTrackers()
	{
		Iterator<EnviroDataTracker> iterator = trackerList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			NBTTagCompound tags = tracker.trackedEntity.getEntityData();
			tags.setFloat("ENVIRO_AIR", tracker.airQuality);
			tags.setFloat("ENVIRO_HYD", tracker.hydration);
			tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
			tags.setFloat("ENVIRO_SAN", tracker.sanity);
		}
		trackerList.clear();
	}
	
	public static void saveAndDeleteWorldTrackers(World world)
	{
		HashMap<String,EnviroDataTracker> tempList = new HashMap<String,EnviroDataTracker>(trackerList);
		Iterator<EnviroDataTracker> iterator = tempList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			
			if(tracker.trackedEntity.worldObj == world)
			{
				NBTTagCompound tags = tracker.trackedEntity.getEntityData();
				tags.setFloat("ENVIRO_AIR", tracker.airQuality);
				tags.setFloat("ENVIRO_HYD", tracker.hydration);
				tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
				tags.setFloat("ENVIRO_SAN", tracker.sanity);
				tracker.isDisabled = true;
				if(tracker.trackedEntity instanceof EntityPlayer)
				{
					trackerList.remove(((EntityPlayer)tracker.trackedEntity).username);
				} else
				{
					trackerList.remove("" + tracker.trackedEntity.entityId);
				}
			}
		}
	}
	
	public static void saveAllWorldTrackers(World world)
	{
		HashMap<String,EnviroDataTracker> tempList = new HashMap<String,EnviroDataTracker>(trackerList);
		Iterator<EnviroDataTracker> iterator = tempList.values().iterator();
		while(iterator.hasNext())
		{
			EnviroDataTracker tracker = iterator.next();
			
			if(tracker.trackedEntity.worldObj == world)
			{
				NBTTagCompound tags = tracker.trackedEntity.getEntityData();
				tags.setFloat("ENVIRO_AIR", tracker.airQuality);
				tags.setFloat("ENVIRO_HYD", tracker.hydration);
				tags.setFloat("ENVIRO_TMP", tracker.bodyTemp);
				tags.setFloat("ENVIRO_SAN", tracker.sanity);
			}
		}
	}
	
	public static EntityPlayer findPlayer(String username)
	{
		World[] worlds = new World[1];
		
		if(EnviroMine.proxy.isClient())
		{
			if(Minecraft.getMinecraft().isIntegratedServerRunning())
			{
				worlds = MinecraftServer.getServer().worldServers;
			} else
			{
				worlds[0] = Minecraft.getMinecraft().thePlayer.worldObj;
			}
		} else
		{
			worlds = MinecraftServer.getServer().worldServers;
		}
		
		for(int i = worlds.length - 1; i >= 0; i -= 1)
		{
			if(worlds[i] == null)
			{
				continue;
			}
			EntityPlayer player = worlds[i].getPlayerEntityByName(username);
			
			if(player != null)
			{
				if(player.isEntityAlive())
				{
					return player;
				}
			}
		}
		
		return null;
	}
	
	public static void createFX(EntityLivingBase entityLiving)
	{
		float rndX = (entityLiving.getRNG().nextFloat() * entityLiving.width * 2) - entityLiving.width;
		float rndY = entityLiving.getRNG().nextFloat() * entityLiving.height;
		float rndZ = (entityLiving.getRNG().nextFloat() * entityLiving.width * 2) - entityLiving.width;
		EnviroDataTracker tracker = lookupTracker(entityLiving);
		
		if(entityLiving instanceof EntityPlayer && !(entityLiving instanceof EntityPlayerMP))
		{
			rndY = -rndY;
		}
		
		if(tracker != null)
		{
			if(tracker.bodyTemp >= 38F && EM_Settings.sweatParticals)
			{
				entityLiving.worldObj.spawnParticle("dripWater", entityLiving.posX + rndX, entityLiving.posY + rndY, entityLiving.posZ + rndZ, 0.0D, 0.0D, 0.0D);
			}
			
			if(tracker.trackedEntity.isPotionActive(EnviroPotion.insanity) && EM_Settings.insaneParticals)
			{
				entityLiving.worldObj.spawnParticle("portal", entityLiving.posX + rndX, entityLiving.posY + rndY, entityLiving.posZ + rndZ, 0.0D, 0.0D, 0.0D);
			}
		}
	}
	
	public static float getTempFalloff(float temp, float dist, int range)
	{
		// sqrt(3*range*range) == sqrt(3)*range == 1.73*range
		float maximum = 1.7320508F * (float)range;
		
		if(dist > maximum)
		{
			return 0;
		} else
		{
			// -temp/max/max*dist*dist+temp == temp/1.73/range/1.73/range*dist*dist+temp == temp/3/range/range*dist*dist+temp
			return (float)((-temp * dist * dist / range / range / 3) + temp);

		}
	}
}
