package enviromine.handlers;

import enviromine.EntityPhysicsBlock;
import enviromine.EnviroPotion;
import enviromine.core.EnviroMine;
import enviromine.trackers.EnviroDataTracker;
import enviromine.trackers.Hallucination;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingSand;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGlassBottle;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumMovingObjectType;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.event.Event.Result;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.Action;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.world.BlockEvent.HarvestDropsEvent;
import net.minecraftforge.event.world.WorldEvent.Save;
import net.minecraftforge.event.world.WorldEvent.Unload;

public class EM_EventManager
{
	@ForgeSubscribe
	public void onEntityJoinWorld(EntityJoinWorldEvent event)
	{
		if(event.entity instanceof EntityLivingBase)
		{
			if(event.entity.worldObj != null)
			{
				if(event.entity.worldObj.isRemote && EnviroMine.proxy.isClient() && Minecraft.getMinecraft().isIntegratedServerRunning())
				{
					return;
				}
			}
			if(EnviroDataTracker.isLegalType((EntityLivingBase)event.entity))
			{
				EnviroDataTracker emTrack = new EnviroDataTracker((EntityLivingBase)event.entity);
				EM_StatusManager.addToManager(emTrack);
				emTrack.loadNBTTags();
				if(!EnviroMine.proxy.isClient() || EnviroMine.proxy.isOpenToLAN())
				{
					EM_StatusManager.syncMultiplayerTracker(emTrack);
				}
			}
		} else if(event.entity instanceof EntityFallingSand && !(event.entity instanceof EntityPhysicsBlock) && !event.world.isRemote)
		{
			EntityFallingSand oldSand = (EntityFallingSand)event.entity;
			EntityPhysicsBlock newSand = new EntityPhysicsBlock(oldSand.worldObj, oldSand.prevPosX, oldSand.prevPosY, oldSand.prevPosZ, oldSand.blockID, oldSand.metadata, true);
			oldSand.setDead();
			event.world.spawnEntityInWorld(newSand);
		}
	}
	
	@ForgeSubscribe
	public void onLivingDeath(LivingDeathEvent event)
	{
		EnviroDataTracker tracker = EM_StatusManager.lookupTracker(event.entityLiving);
		if(tracker != null)
		{
			if(event.entityLiving instanceof EntityPlayer)
			{
				EntityPlayer player = EM_StatusManager.findPlayer(((EntityPlayer)event.entityLiving).username);
				
				if(player != null)
				{
					tracker.resetData();
					EM_StatusManager.saveAndRemoveTracker(tracker);
				} else
				{
					tracker.resetData();
					EM_StatusManager.saveAndRemoveTracker(tracker);
				}
			} else
			{
				tracker.resetData();
				EM_StatusManager.saveAndRemoveTracker(tracker);
			}
		}
	}
	
	@ForgeSubscribe
	public void onEntityAttacked(LivingAttackEvent event)
	{
		if(event.entityLiving.worldObj.isRemote)
		{
			return;
		}
		
		Entity attacker = event.source.getEntity();
		
		if(attacker != null)
		{
			EnviroDataTracker tracker = EM_StatusManager.lookupTracker(event.entityLiving);
			
			if(tracker != null)
			{
				if(attacker instanceof EntityZombie)
				{
					tracker.sanity -= 1F;
				} else if(attacker instanceof EntityEnderman)
				{
					tracker.sanity -= 5F;
				}
			}
		}
	}
	
	@ForgeSubscribe
	public void onPlayerInteract(PlayerInteractEvent event)
	{
		ItemStack item = event.entityPlayer.getCurrentEquippedItem();
		if(event.getResult() != Result.DENY && event.action == Action.RIGHT_CLICK_BLOCK && item != null)
		{
			if(item.getItem() instanceof ItemBlock && !event.entityPlayer.worldObj.isRemote)
			{
				int adjCoords[] = getAdjacentBlockCoordsFromSide(event.x, event.y, event.z, event.face);
				EM_PhysManager.schedulePhysUpdate(event.entityPlayer.worldObj, adjCoords[0], adjCoords[1], adjCoords[2], true, false);
			} else if(item.getItem() instanceof ItemGlassBottle && !event.entityPlayer.worldObj.isRemote)
			{
				if(event.entityPlayer.worldObj.getBlockId(event.x, event.y, event.z) == Block.cauldron.blockID && event.entityPlayer.worldObj.getBlockMetadata(event.x, event.y, event.z) > 0)
				{
					fillBottle(event.entityPlayer.worldObj, event.entityPlayer, event.x, event.y, event.z, item, event);
				}
			} else if(item.getItem() == null && !event.entityPlayer.worldObj.isRemote)
			{
				drinkWater(event.entityPlayer, event);
			}
		} else if(event.getResult() != Result.DENY && event.action == Action.LEFT_CLICK_BLOCK)
		{
			EM_PhysManager.schedulePhysUpdate(event.entityPlayer.worldObj, event.x, event.y, event.z, true, false);
		} else if(event.getResult() != Result.DENY && event.action != Action.LEFT_CLICK_BLOCK && item == null && !event.entityPlayer.worldObj.isRemote)
		{
			drinkWater(event.entityPlayer, event);
		} else if(event.getResult() != Result.DENY && event.action == Action.RIGHT_CLICK_AIR && item != null)
		{
			if(item.getItem() instanceof ItemGlassBottle && !event.entityPlayer.worldObj.isRemote)
			{
				if(!(event.entityPlayer.worldObj.getBlockId(event.x, event.y, event.z) == Block.cauldron.blockID && event.entityPlayer.worldObj.getBlockMetadata(event.x, event.y, event.z) > 0))
				{
					fillBottle(event.entityPlayer.worldObj, event.entityPlayer, event.x, event.y, event.z, item, event);
				}
			}
		}
	}

	public static void fillBottle(World world, EntityPlayer player, int x, int y, int z, ItemStack item, PlayerInteractEvent event)
	{
        MovingObjectPosition movingobjectposition = getMovingObjectPositionFromPlayer(world, player, true);

        if (movingobjectposition == null)
        {
            return;
        }
        else
        {
            if (movingobjectposition.typeOfHit == EnumMovingObjectType.TILE)
            {
                int i = movingobjectposition.blockX;
                int j = movingobjectposition.blockY;
                int k = movingobjectposition.blockZ;
                
                boolean isValidCauldron = (player.worldObj.getBlockId(i, j, k) == Block.cauldron.blockID && player.worldObj.getBlockMetadata(i, j, k) > 0);

                if (!world.canMineBlock(player, i, j, k))
                {
                    return;
                }

                if (!player.canPlayerEdit(i, j, k, movingobjectposition.sideHit, item))
                {
                    return;
                }

                if (world.getBlockMaterial(i, j, k) == Material.water || isValidCauldron)
                {
                    Item newItem = Item.potion;
                    
                    switch(getWaterType(world, i, j, k))
                    {
                    	case 0:
                    	{
                    		newItem = Item.potion;
                    		break;
                    	}
                    	case 1:
                    	{
                    		newItem = EnviroMine.badWaterBottle;
                    		break;
                    	}
                    	case 2:
                    	{
                    		newItem = EnviroMine.saltWaterBottle;
                    		break;
                    	}
                    	case 3:
                    	{
                    		newItem = EnviroMine.coldWaterBottle;
                    		break;
                    	}
                    }
                    
                    if(isValidCauldron && (world.getBlockId(i, j - 1, k) == Block.fire.blockID || world.getBlockId(i, j - 1, k) == Block.lavaMoving.blockID || world.getBlockId(i, j - 1, k) == Block.lavaStill.blockID))
                    {
                    	newItem = Item.potion;
                    }
                    
                    if(isValidCauldron)
                    {
						player.worldObj.setBlockMetadataWithNotify(i, j, k, player.worldObj.getBlockMetadata(i, j, k)-1, 2);
                    }
                    
                    --item.stackSize;
                	
                    if (item.stackSize <= 0)
                    {
                    	item.itemID = newItem.itemID;
                    	item.stackSize = 1;
                    	item.setItemDamage(0);
                    } else
                    {
                        EntityItem itemDrop = player.dropPlayerItem(new ItemStack(newItem.itemID, 1, 0));
                        itemDrop.delayBeforeCanPickup = 0;
                    }
                    
                    event.setCanceled(true);
                }
            }

            return;
        }
	}

	public static void drinkWater(EntityPlayer entityPlayer, PlayerInteractEvent event)
	{
		EnviroDataTracker tracker = EM_StatusManager.lookupTracker(entityPlayer);
		MovingObjectPosition mop = getMovingObjectPositionFromPlayer(entityPlayer.worldObj, entityPlayer, true);
		
		if(mop != null)
		{
			if (mop.typeOfHit == EnumMovingObjectType.TILE)
            {
                int i = mop.blockX;
                int j = mop.blockY;
                int k = mop.blockZ;
                
                boolean isValidCauldron = (entityPlayer.worldObj.getBlockId(i, j, k) == Block.cauldron.blockID && entityPlayer.worldObj.getBlockMetadata(i, j, k) > 0);
                
				if(entityPlayer.worldObj.getBlockMaterial(i, j, k) == Material.water || isValidCauldron)
				{
					if(tracker != null)
					{
						int type = getWaterType(entityPlayer.worldObj, i, j, k);
						if(type == 0)
						{
							if(tracker.bodyTemp >= 21)
							{
								tracker.bodyTemp -= 1;
							}
							tracker.hydrate(10F);
						} else if(type == 1)
						{
							if(entityPlayer.getRNG().nextInt(2) == 0)
							{
								entityPlayer.addPotionEffect(new PotionEffect(Potion.hunger.id, 200));
							}
							if(entityPlayer.getRNG().nextInt(4) == 0)
							{
								entityPlayer.addPotionEffect(new PotionEffect(Potion.poison.id, 200));
							}
							tracker.hydrate(10F);
						} else if(type == 2)
						{
							if(entityPlayer.getRNG().nextInt(1) == 0)
							{
								entityPlayer.addPotionEffect(new PotionEffect(EnviroPotion.dehydration.id, 600));
							}
							tracker.hydrate(5F);
						} else if(type == 3)
						{
							if(tracker.bodyTemp >= 5)
							{
								tracker.bodyTemp -= 5;
							}
							tracker.hydrate(10F);
						}
						
						if(isValidCauldron)
						{
							entityPlayer.worldObj.setBlockMetadataWithNotify(i, j, k, entityPlayer.worldObj.getBlockMetadata(i, j, k)-1, 2);
						}
						
						event.setCanceled(true);
					}
				}
            }
		}
	}

	public static int getWaterType(World world, int x, int y, int z)
	{
		BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
		
		if(biome == null)
		{
			return 0;
		}
		
		if(biome.biomeName == BiomeGenBase.swampland.biomeName || biome.biomeName == BiomeGenBase.jungle.biomeName || biome.biomeName == BiomeGenBase.jungleHills.biomeName || y < 48)
		{
			return 1;
		} else if(biome.biomeName == BiomeGenBase.frozenOcean.biomeName || biome.biomeName == BiomeGenBase.ocean.biomeName || biome.biomeName == BiomeGenBase.beach.biomeName)
		{
			return 2;
		} else if(biome.biomeName == BiomeGenBase.icePlains.biomeName || biome.biomeName == BiomeGenBase.taiga.biomeName || biome.biomeName == BiomeGenBase.taigaHills.biomeName)
		{
			return 3;
		} else
		{
			return 0;
		}
	}

	public int[] getAdjacentBlockCoordsFromSide(int x, int y, int z, int side)
	{
		int[] coords = new int[3];
		coords[0] = x;
		coords[1] = y;
		coords[2] = z;
		
        ForgeDirection dir = ForgeDirection.getOrientation(side);
        switch(dir)
        {
        	case NORTH:
        	{
        		coords[2] -= 1;
        		break;
        	}
        	case SOUTH:
        	{
        		coords[2] += 1;
        		break;
        	}
        	case WEST:
        	{
        		coords[0] -= 1;
        		break;
        	}
        	case EAST:
        	{
        		coords[0] += 1;
        		break;
        	}
        	case UP:
        	{
        		coords[1] += 1;
        		break;
        	}
        	case DOWN:
        	{
        		coords[1] -= 1;
        		break;
        	}
        	case UNKNOWN:
        	{
        		break;
        	}
        }
        
        return coords;
	}

	@ForgeSubscribe
	public void onBreakBlock(HarvestDropsEvent event)
	{
		if(event.world.isRemote)
		{
			return;
		}
		
		if(event.harvester != null)
		{
			if(event.getResult() != Result.DENY && !event.harvester.capabilities.isCreativeMode)
			{
				EM_PhysManager.schedulePhysUpdate(event.world, event.x, event.y, event.z, true, false);
			}
		}
	}
	
	@ForgeSubscribe
	public void onLivingUpdate(LivingUpdateEvent event)
	{
		if(event.entityLiving.worldObj.isRemote)
		{
			if(event.entityLiving.getRNG().nextInt(5) == 0)
			{
				EM_StatusManager.createFX(event.entityLiving);
			}
			
			if(event.entityLiving instanceof EntityPlayer && EnviroMine.proxy.isClient())
			{
				if(event.entityLiving.isPotionActive(EnviroPotion.insanity))
				{
					if(event.entityLiving.getRNG().nextInt(75) == 0)
					{
						if(EnviroMine.proxy.isClient())
						{
							new Hallucination(event.entityLiving);
						}
					}
				}
				
				Hallucination.update();
			}
			return;
		}
		
		if(event.entityLiving instanceof EntityPlayer)
		{
			EnviroDataTracker tracker = EM_StatusManager.lookupTracker(event.entityLiving);
			ItemStack item = null;
			int itemUse = 0;
			
			if(((EntityPlayer)event.entityLiving).isPlayerSleeping())
			{
				if(((EntityPlayer)event.entityLiving).isPlayerFullyAsleep())
				{
					tracker.sanity += 5.0F;
				} else
				{
					tracker.sanity += 1.0F;
				}
			}
			
			if(((EntityPlayer)event.entityLiving).isUsingItem())
			{
				item = ((EntityPlayer)event.entityLiving).getHeldItem();
				
				if(tracker != null)
				{
					itemUse = tracker.getAndIncrementItemUse();
				} else
				{
					itemUse = 0;
				}
			} else
			{
				item = null;
				
				if(tracker != null)
				{
					tracker.resetItemUse();
				} else
				{
					itemUse = 0;
				}
			}
			
			if(item != null && tracker != null)
			{
				if(itemUse >= Item.itemsList[item.itemID].getMaxItemUseDuration(item) - 1)
				{
					itemUse = 0;
					if(item.itemID == Item.potion.itemID)
					{
						if(tracker.bodyTemp >= 25F)
						{
							tracker.bodyTemp -= 5F;
						}
						tracker.hydrate(25.0F);
					} else if(item.itemID == Item.melon.itemID || item.itemID == Item.carrot.itemID || item.itemID == Item.goldenCarrot.itemID || item.itemID == Item.appleRed.itemID)
					{
						tracker.hydrate(5.0F);
					} else if(item.itemID == Item.appleGold.itemID)
					{
						if(item.isItemDamaged())
						{
							tracker.hydration = 100F;
							tracker.sanity = 100F;
							tracker.airQuality = 100F;
							tracker.bodyTemp = 20F;
							if(!EnviroMine.proxy.isClient() || EnviroMine.proxy.isOpenToLAN())
							{
								EM_StatusManager.syncMultiplayerTracker(tracker);
							}
						} else
						{
							tracker.sanity = 100F;
							tracker.hydrate(10F);
						}
					} else if(item.itemID == Item.bowlSoup.itemID || item.itemID == Item.pumpkinPie.itemID)
					{
						if(tracker.bodyTemp <= 50F)
						{
							tracker.bodyTemp += 1F;
						}
						tracker.hydrate(5F);
					} else if(item.itemID == Item.bucketMilk.itemID)
					{
						tracker.hydrate(10F);
					} else if(item.itemID == Item.porkCooked.itemID || item.itemID == Item.beefCooked.itemID || item.itemID == Item.chickenCooked.itemID || item.itemID == Item.bakedPotato.itemID)
					{
						if(tracker.bodyTemp <= 50F)
						{
							tracker.bodyTemp += 1F;
						}
						if(!EnviroMine.proxy.isClient() || EnviroMine.proxy.isOpenToLAN())
						{
							EM_StatusManager.syncMultiplayerTracker(tracker);
						}
					} else if(item.itemID == Item.appleRed.itemID)
					{
						tracker.hydrate(5F);
					} else if(item.itemID == Item.rottenFlesh.itemID || item.itemID == Item.spiderEye.itemID || item.itemID == Item.poisonousPotato.itemID)
					{
						tracker.dehydrate(5F);
					}
				}
			}
		}
	}

	@ForgeSubscribe
	public void onWorldUnload(Unload event)
	{
		EM_StatusManager.saveAndDeleteWorldTrackers(event.world);
	}

	@ForgeSubscribe
	public void onWorldSave(Save event)
	{
		EM_StatusManager.saveAllWorldTrackers(event.world);
	}

    protected static MovingObjectPosition getMovingObjectPositionFromPlayer(World par1World, EntityPlayer par2EntityPlayer, boolean par3)
    {
        float f = 1.0F;
        float f1 = par2EntityPlayer.prevRotationPitch + (par2EntityPlayer.rotationPitch - par2EntityPlayer.prevRotationPitch) * f;
        float f2 = par2EntityPlayer.prevRotationYaw + (par2EntityPlayer.rotationYaw - par2EntityPlayer.prevRotationYaw) * f;
        double d0 = par2EntityPlayer.prevPosX + (par2EntityPlayer.posX - par2EntityPlayer.prevPosX) * (double)f;
        double d1 = par2EntityPlayer.prevPosY + (par2EntityPlayer.posY - par2EntityPlayer.prevPosY) * (double)f + (double)(par1World.isRemote ? par2EntityPlayer.getEyeHeight() - par2EntityPlayer.getDefaultEyeHeight() : par2EntityPlayer.getEyeHeight()); // isRemote check to revert changes to ray trace position due to adding the eye height clientside and player yOffset differences
        double d2 = par2EntityPlayer.prevPosZ + (par2EntityPlayer.posZ - par2EntityPlayer.prevPosZ) * (double)f;
        Vec3 vec3 = par1World.getWorldVec3Pool().getVecFromPool(d0, d1, d2);
        float f3 = MathHelper.cos(-f2 * 0.017453292F - (float)Math.PI);
        float f4 = MathHelper.sin(-f2 * 0.017453292F - (float)Math.PI);
        float f5 = -MathHelper.cos(-f1 * 0.017453292F);
        float f6 = MathHelper.sin(-f1 * 0.017453292F);
        float f7 = f4 * f5;
        float f8 = f3 * f5;
        double d3 = 5.0D;
        if (par2EntityPlayer instanceof EntityPlayerMP)
        {
            d3 = ((EntityPlayerMP)par2EntityPlayer).theItemInWorldManager.getBlockReachDistance();
        }
        Vec3 vec31 = vec3.addVector((double)f7 * d3, (double)f6 * d3, (double)f8 * d3);
        return par1World.rayTraceBlocks_do_do(vec3, vec31, par3, !par3);
    }
}