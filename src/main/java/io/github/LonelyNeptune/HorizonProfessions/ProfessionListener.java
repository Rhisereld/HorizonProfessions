package io.github.LonelyNeptune.HorizonProfessions;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.milkbowl.vault.permission.Permission;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerFishEvent.State;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.metadata.Metadatable;
import org.bukkit.plugin.Plugin;

/**
 * ProfessionListener contains all the methods that are called when certain events happen in game.
 */
public class ProfessionListener implements Listener
{
	private Plugin plugin;
	private Permission perms;
	private FileConfiguration data;
	private static FileConfiguration config;
	private boolean isHealingOther;							//Used to cancel healing self if the player is healing another.
	private Set<UUID> notified = new HashSet<>();	//Used to ensure players are not spammed with the reason they are not gaining experience.
	
	ProfessionListener(Plugin plugin, Permission perms, FileConfiguration data, FileConfiguration config)
	{
		this.perms = perms;
		this.plugin = plugin;
		this.data = data;
		ProfessionListener.config = config;
	}
	
	// updateConfig() updates the config file in the event of a configuration reload.
	static void updateConfig(FileConfiguration config)
	{
		ProfessionListener.config = config;
	}
	
	//Called when a player joins the server
	@EventHandler(priority = EventPriority.MONITOR)
	void onPlayerJoin(PlayerJoinEvent event)
	{
		//Add the player to the correct permissions groups for their professions
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		ProfessionStats prof = new ProfessionStats(perms, data, config, uuid);
		for (String pr: prof.getProfessions())
			perms.playerAdd(null, player, config.getString("permission_prefix") + "." + pr + "."
					+ prof.getTierName(prof.getTier(pr)));
		
		//Add the player's name and UUID to file.
		data.set("data." + uuid + ".name", player.getName());
	}
	
	//Called when a player leaves the server
	@EventHandler(priority = EventPriority.MONITOR)
	void onPlayerLeave(PlayerQuitEvent event)
	{
		//Remove the player from all permission groups for professions
		ProfessionStats prof = new ProfessionStats(perms, data, config, event.getPlayer().getUniqueId());
		for (String pr: prof.getProfessions())
			perms.playerRemove((String) null, event.getPlayer(), config.getString("permission_prefix") + "." + pr + "." 
					+ prof.getTierName(prof.getTier(pr)));
	}
	
	//Called when a monster or player dies.
	@EventHandler(priority = EventPriority.MONITOR)
	void onMonsterDeath(EntityDeathEvent event)
	{
		Entity entity = event.getEntity();
		EntityDamageByEntityEvent dEvent;
		Player player;
		Set<String> list;

		//Check that it was killed by another entity		
		if(!(entity.getLastDamageCause() instanceof EntityDamageByEntityEvent))
			return;
		
		dEvent = (EntityDamageByEntityEvent) entity.getLastDamageCause();
		
		//Check that it was killed by a player
		if(dEvent.getDamager() instanceof Player)
		{
			player = (Player) dEvent.getDamager();
			
			//Check if the monster is contained within the config
			ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
			
			for (String p: prof.getProfessions())
			{
				//If there's no configuration for that profession, skip it.
				try {list = config.getConfigurationSection("slaying." + p).getKeys(false);}
				catch (NullPointerException e)
				{ continue; }
				
				for (String monster: list)
					//If found, award experience for it.
					if (entity.getType().toString().equalsIgnoreCase(monster))
					{
						if (!prof.isPracticeFatigued(p))
							addExperience(player, p, config.getInt("slaying." + p + "." + monster));
						return;
					}
			}
		}
		else if (dEvent.getDamager() instanceof Arrow)
		{	
			Arrow arrow = (Arrow) dEvent.getDamager();
	        if (!(arrow.getShooter() instanceof Player))
	        	return;
	        
	        player = (Player) arrow.getShooter();
			
			//Check if the monster is contained within the config
			ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
			
			for (String p: prof.getProfessions())
			{
				//If there's no configuration for that profession, skip it.
				try {list = config.getConfigurationSection("slaying." + p).getKeys(false);}
				catch (NullPointerException e)
				{ continue; }
				
				for (String monster: list)
					//If found, award experience for it.
					if (entity.getType().toString().equalsIgnoreCase(monster))
					{
						if (!prof.isPracticeFatigued(p))
							addExperience(player, p, config.getInt("slaying." + p + "." + monster));
						return;
					}
			}
		}

	}
	
	//Called when a player right clicks something
	@EventHandler(priority = EventPriority.MONITOR)
	void onPlayerInteract(PlayerInteractEntityEvent event)
	{
		Player player = event.getPlayer();
		isHealingOther = true;
		
		//Check that the player right-clicked on another player.
		if (!(event.getRightClicked() instanceof Player))
			return;

    	//Check if the item in hand fits any of the items specified in the configuration file.		
    	Set <String> items;
    	try { items = config.getConfigurationSection("healing.").getKeys(false); }
    	catch (NullPointerException e)
    	{ return; }
    	
    	String item = null;
    	for (String i: items)
    		if (player.getItemInHand().getType().toString().equalsIgnoreCase(i))
    			item = i;
    	
    	//If the item isn't found, it's not a healing item.
    	if (item == null)
    		return;
    	
		//Check if the amount to heal is in the config
    	Set<String> professionsRequired;
    	try { professionsRequired = config.getConfigurationSection("healing." + item).getKeys(false); }
    	catch (NullPointerException e)
    	{ return; }
    	
    	for (String p: professionsRequired)
    	{
    		ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
    		
    		if (p == null)
    			continue;
    		
        	ProfessionHandler profHandler = new ProfessionHandler(perms, data, config);
        	
        	double amountToHeal = config.getInt("healing." + item + "." + p + "."
					+ profHandler.getTierName(prof.getTier(p)));
        	if (amountToHeal == 0)
        	{
        		player.sendMessage(ChatColor.RED + "You do not have the skill required to do this!");
        		return;
        	}
        	
        	//Check that the recipient has missing health.
    		Player recipient = (Player) event.getRightClicked();
    		if (recipient.getHealth() >= 20)
    		{
    			player.sendMessage(ChatColor.YELLOW + recipient.getName() + " does not need bandaging!");
    			return;
    		}  
    		
        	//Check that it won't take you over the maximum amount of health.
        	if (recipient.getHealth() + amountToHeal > 20)
        		amountToHeal = 20 - recipient.getHealth();
    		
    		player.sendMessage(ChatColor.YELLOW + "Bandaging...");
    		String name = player.getCustomName();
    		if (name == null)
    			name = player.getName();
    		recipient.sendMessage(ChatColor.YELLOW + name + " is bandaging you...");
    		
    		//Schedule the task in one second.
    		makeDelayedTask(player, recipient, amountToHeal, item, p, player.getLocation(), recipient.getLocation());
    	}
	}
	
	//Called when a player right-clicks
	@EventHandler(priority = EventPriority.MONITOR)
	void onRightClick(PlayerInteractEvent event)
	{
		Player player = event.getPlayer();
		
		//Check that it's a right click
		if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
			return;
		
		//If the player is healing another person, they're not healing themself.
		if (isHealingOther)
		{
			isHealingOther = false;
			return;
		}
		
		//Check if the item in hand fits any of the items specified in the configuration file.	
		Set<String> items;
    	try { items = config.getConfigurationSection("healing.").getKeys(false); }
    	catch (NullPointerException e)
    	{ return; }
    	
    	String item = null;
    	for (String i: items)
    		if (player.getItemInHand().getType().toString().equalsIgnoreCase(i))
    			item = i;
    	
    	//If the item isn't found, it's not a healing item.
    	if (item == null)
    		return;
    	
    	Set<String> professionReqs;
    	try { professionReqs = config.getConfigurationSection("healing." + item).getKeys(false); }
    	catch (NullPointerException e)
    	{ return; }
    	
    	for (String p: professionReqs)
    	{
    		ProfessionHandler profHandler = new ProfessionHandler(perms, data, config);
    		
    		if (p == null || !profHandler.isValidProfession(p))
    			continue;
    		
    		//Check if the amount to heal is in the config
        	ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
        	
        	double amountToHeal = config.getInt("healing." + item + "." + p + "."
					+ profHandler.getTierName(prof.getTier(p)));
        	if (amountToHeal == 0)
        	{
        		player.sendMessage(ChatColor.RED + "You do not have the skill required to do this!");
        		return;
        	}
        	    	
        	//Check that the player has missing health.
        	if (player.getHealth() >= 20)
        	{
        		player.sendMessage(ChatColor.YELLOW + "You do not need bandaging!");
        		return;
        	}  
        	
        	//Check that it won't take you over the maximum amount of health.
        	if (player.getHealth() + amountToHeal > 20)
        		amountToHeal = 20 - player.getHealth();
        			
        	player.sendMessage(ChatColor.YELLOW + "Bandaging...");
        			
        	//Schedule the task in one second.
        	makeDelayedTask(player, player, amountToHeal, item, p, player.getLocation(),  player.getLocation());
    	}
	}
	
	@EventHandler(priority = EventPriority.NORMAL)
	void onDamage(EntityDamageByEntityEvent event)
	{
		Set<String> professions;
		
		//If it isn't a player, don't mess with this event.
		if (!(event.getDamager() instanceof Player))
			return;

		//Get the damage and multiply it by the relevant modifiers.
		try {professions = config.getConfigurationSection("damageModifier").getKeys(false);}
		catch (NullPointerException e)
		{ return; }
		
		Player player = (Player) event.getDamager();
		ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
		double damage = event.getDamage();

		for (String p: professions)
		{
			//Check that the player is using the correct item
			String weaponReq = config.getString("damageModifier." + p + ".weaponReq");
			if (weaponReq != null && !weaponReq.equals("[ANY]") && !weaponReq.equals("ANY"))
			{
				List<String> weaponReqList = config.getStringList("damageModifier." + p + ".weaponReq");
				boolean weaponFound = false;
				for (String w: weaponReqList)
					if (player.getItemInHand().getType().toString().equalsIgnoreCase(w))
						weaponFound = true;
				
				if (!weaponFound)
					return;
			}
			
			damage = damage * config.getInt("damageModifier." + p + "." + prof.getTierName(prof.getTier(p)), 100) / 100;
		}

		event.setDamage(damage);
	}
	
	@EventHandler(priority = EventPriority.NORMAL)
	void onArrowShoot(EntityDamageByEntityEvent event)
	{
		if (event.getDamager() instanceof Arrow)
	    {
	        Arrow arrow = (Arrow) event.getDamager();
	        if (arrow.getShooter() instanceof Player)
	        {
	        	Player shooter = (Player) arrow.getShooter();
	        	
	    		//Get the damage and multiply it by the relevant modifiers.
	        	Set<String> professions;
	    		try {professions = config.getConfigurationSection("damageModifier").getKeys(false);}
	    		catch (NullPointerException e)
	    		{ return; }
	    		
	    		ProfessionStats prof = new ProfessionStats(perms, data, config, shooter.getUniqueId());
	    		double damage = event.getDamage();

	    		for (String p: professions)
	    		{
	    			//Check that the player is using the correct item
	    			String weaponReq = config.getString("damageModifier." + p + ".weaponReq");
	    			if (weaponReq != null && !weaponReq.equals("[ANY]") && !weaponReq.equals("ANY"))
	    			{
	    				List<String> weaponReqList = config.getStringList("damageModifier." + p + ".weaponReq");
	    				boolean weaponFound = false;
	    				for (String w: weaponReqList)
	    					if (shooter.getItemInHand().getType().toString().equalsIgnoreCase(w))
	    						weaponFound = true;
	    				
	    				if (!weaponFound)
	    					return;
	    			}	
	    			damage = damage * config.getInt("damageModifier." + p + "." + prof.getTierName(prof.getTier(p)), 100) / 100;
	    		}
	    		event.setDamage(damage);
	        }
	    }
	}
	
	//Called when a block is broken
	@EventHandler(priority = EventPriority.HIGH)
	void onBreakBlock(BlockBreakEvent event)
	{
		Player player = event.getPlayer();
		
		//If the player is in creative mode don't mess with the event
		if (player.getGameMode().equals(GameMode.CREATIVE))
			return;
		
		//Check if the block is contained within the config
		Set<String> configBlocks;
		int exp = 0;
		String professionReq = null;
		String tierReq = null;
		
		ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
		
		for(String p: prof.getProfessions())
			for (String t: prof.getTiers())
			{
				try { configBlocks = config.getConfigurationSection("breakBlocks." + p + "." + t).getKeys(false); }
				catch (NullPointerException e)
				{ continue; }
				
				for (String b: configBlocks)
					if (event.getBlock().getType().toString().equalsIgnoreCase(b))
					{
						exp = config.getInt("breakBlocks." + p + "." + t + "." + b);
						professionReq = p;
						tierReq = t;
						break;
					}
			}
		
		//If not found, nothing to do here.
		if (professionReq == null || tierReq == null)
		{
			return;
		}
		
		//If the player doesn't have at least the tier, cancel the event.
		long place_cooldown = config.getLong("place_cooldown");
		
		if (!prof.hasTier(professionReq, tierReq))
		{
			player.sendMessage(ChatColor.RED + "You aren't skilled enough to break that!");
			event.setCancelled(true);
		}
		//Otherwise award some experience
		else if ((!event.getBlock().hasMetadata("timeplaced") 
				|| System.currentTimeMillis() - getMetadataLong(event.getBlock(), "timeplaced") > place_cooldown)
				&& !prof.isPracticeFatigued(professionReq))
			addExperience(player, professionReq, exp);
	}
	
	//Called when a block is placed.
	@EventHandler (priority = EventPriority.MONITOR)
	void onBlockPlace(BlockPlaceEvent event)
	{
		Player player = event.getPlayer();
		
		//Record the time placed so that placing cooldowns can be implemented.
		event.getBlock().setMetadata("timeplaced", new FixedMetadataValue(plugin, System.currentTimeMillis()));
		
		//Check if the block is contained within the config
		Set<String> configBlocks;
		int exp = 0;
		String professionReq = null;
		String tierReq = null;
		
		ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
		
		for(String p: prof.getProfessions())
			for (String t: prof.getTiers())
			{
				try { configBlocks = config.getConfigurationSection("placeBlocks." + p + "." + t).getKeys(false); }
				catch (NullPointerException e)
				{ continue; }
				
				for (String b: configBlocks)
					if (event.getBlock().getType().toString().equalsIgnoreCase(b))
					{
						exp = config.getInt("placeBlocks." + p + "." + t + "." + b);
						professionReq = p;
						tierReq = t;
						break;
					}
			}
		
		//If not found, nothing to do here.
		if (professionReq == null || tierReq == null)
			return;
		
		//If the player doesn't have at least the tier, cancel the event.		
		if (!prof.hasTier(professionReq, tierReq))
		{
			player.sendMessage(ChatColor.RED + "You aren't skilled enough to place that!");
			event.setCancelled(true);
		}
		//Otherwise award some experience
		else if (!prof.isPracticeFatigued(professionReq))
			addExperience(player, professionReq, exp);
	}
	
	@EventHandler (ignoreCancelled = true)
    public void onWaterBreakingWheat(BlockFromToEvent event) 
	{
        if ((event.getBlock().getType().equals(Material.WATER) || event.getBlock().getType().equals(Material.STATIONARY_WATER))
        		&& event.getToBlock().getType().equals(Material.CROPS))
            event.getToBlock().setType(Material.AIR);
    }
	
	//Called when a player successfully catches a fish.
	@EventHandler(priority = EventPriority.HIGH)
	public void onFishing(PlayerFishEvent event)
	{
		if (!event.getState().equals(State.CAUGHT_FISH))
			return;
		
		ProfessionStats prof = new ProfessionStats(perms, data, config, event.getPlayer().getUniqueId());
		String professionReq = config.getString("fishing.professionReq");
		Bukkit.getLogger().info("professionReq: " + professionReq); //TODO
		if (professionReq == null)
			return;
		String tierReq = config.getString("fishing.tierReq");
		Bukkit.getLogger().info("tierReq: " + tierReq); //TODO
		if (tierReq == null)
			return;
		int exp = config.getInt("fishing.exp");
		
		List<String> professions = prof.getProfessions();
		
		for (String p: professions)
			if (professionReq.equalsIgnoreCase(p))
			{
				Bukkit.getLogger().info("Profession matched: " + p); //TODO
				
				if (!prof.hasTier(professionReq, tierReq))
				{
					event.setCancelled(true);
					Bukkit.getLogger().info("Drop cancelled."); //TODO
				}
				else
					if (!prof.isPracticeFatigued(professionReq))
					{
						addExperience(event.getPlayer(), professionReq, exp);
						Bukkit.getLogger().info("Experience added."); //TODO
					}
			}
	}
	
	private void makeDelayedTask(final Player player, final Player recipient, final double amountToHeal,
								 final String item, final String profession, final Location playerLoc,
								 final Location recipientLoc)
	{
		//After a second, perform the action.
		plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new Runnable() 
		{
			public void run() 
			{				
				//Check that the player is still in roughly the same location
				if (Math.abs(player.getLocation().getX() - playerLoc.getX()) > 1
						|| Math.abs(player.getLocation().getY() - playerLoc.getY()) > 1
						|| Math.abs(player.getLocation().getZ() - playerLoc.getZ()) > 1)
				{
					player.sendMessage(ChatColor.YELLOW + "You cannot move while bandaging!");
					return;
				}
				
				String name = player.getCustomName();
				if (name == null)
					name = player.getName();
				
				//Check that the recipient is still in roughly the same location
				//Skip if self-heal
				if (!player.equals(recipient))
					if (Math.abs(recipient.getLocation().getX() - recipientLoc.getX()) > 1
						|| Math.abs(recipient.getLocation().getY() - recipientLoc.getY()) > 1
						|| Math.abs(recipient.getLocation().getZ() - recipientLoc.getZ()) > 1)
					{
						player.sendMessage(ChatColor.YELLOW + "You cannot bandage your patient while they are moving!");
						recipient.sendMessage(ChatColor.YELLOW + name + " cannot bandage you while you are moving!");
						return;
					}
				
				//Remove item from player's inventory.
	    		player.getInventory().removeItem(new ItemStack(Material.getMaterial(item.toUpperCase()), 1));
	    		player.updateInventory();
	    			
	    		//Heal the other player.
	    		recipient.setHealth(recipient.getHealth() + amountToHeal);

	    		//Award experience.
	    		ProfessionStats prof = new ProfessionStats(perms, data, config, player.getUniqueId());
	    		
	    		if (!prof.isPracticeFatigued(profession))
	    			addExperience(player, profession, config.getInt("healing." + item + ".exp"));
	    			
	    		//Notify both parties.
	    		if (!player.equals(recipient))
	    		{	    		
	    			player.sendMessage(ChatColor.YELLOW + "You bandaged " + recipient.getName() + "'s wounds.");
	    			recipient.sendMessage(ChatColor.YELLOW + player.getName() + " bandaged your wounds.");
	    		}
	    		else
	    			player.sendMessage(ChatColor.YELLOW + "You bandaged your wounds.");
			  }
			}, 20);
	}
	

	/**
	 * getMetadataLong() retrieves metadata from an object using a key.
	 * @param object - the object the metadata is attached to.
	 * @param key - the key the metadata is under.
	 * @return metadata
	 */
	private long getMetadataLong(Metadatable object, String key) 
	{
		List<MetadataValue> values = object.getMetadata(key);  
		for (MetadataValue value : values) 
		{
			// Plugins are singleton objects, so using == is safe here
			if (value.getOwningPlugin() == plugin) 
			{
				return value.asLong();
			}
		}
		return 0;
	}
	
	// addExperience() calls ProfessionStats to add experience, and also provides messages to the player if giving this
	// experience fails.
	private void addExperience(Player player, String profession, int exp)
	{
		UUID uuid = player.getUniqueId();
		ProfessionStats prof = new ProfessionStats(perms, data, config, uuid);
		int result = prof.addExperience(profession, exp);
		
		if (notified.contains(uuid))
			return;
		
		if (result == 5)
		{
			notified.add(uuid);
			player.sendMessage(ChatColor.YELLOW + "You cannot gain any experience because you are on cooldown.");
		}
		
		if (result == 4)
		{
			notified.add(uuid);
			player.sendMessage(ChatColor.YELLOW + "You cannot gain any experience because you have reached the " +
					"maximum number of tiers permitted.");
		}
		if (result == 3)
		{
			notified.add(uuid);
			player.sendMessage(ChatColor.YELLOW + "You cannot gain any experience because you have reached the " +
					"maximum tier in " + profession);
		}
		if (result == 2)
		{
			notified.add(uuid);
			player.sendMessage(ChatColor.YELLOW + "You cannot gain any experience because you have not yet claimed " +
					"all your tiers.");
		}
	}
}
