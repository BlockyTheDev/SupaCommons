package com.supaham.commons.bukkit.players;

import com.supaham.commons.bukkit.TickerTask;
import com.supaham.commons.bukkit.listeners.PlayerListeners;
import com.supaham.commons.bukkit.modules.CommonModule;
import com.supaham.commons.bukkit.modules.ModuleContainer;
import com.supaham.commons.bukkit.potion.Potion;
import com.supaham.commons.bukkit.potion.PotionEffectManager;
import com.supaham.commons.bukkit.potion.Potions;
import com.supaham.commons.bukkit.utils.LocationUtils;
import com.supaham.commons.state.State;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nonnull;

/**
 * Supa-awesome player freezing class. This class utilizes flight and flight speed especially for
 * handling players midair and stop them from moving by setting them as flying and their flight
 * speed.
 *
 * <p/>
 * Since this code does modify a player's walk speed when they get frozen, if for whatever case
 * the player retains the zero walk speed, you may use {@link PlayerListeners#defaultSpeeds(Plugin)}
 * in order to reset their default walk and flight speed on join.
 *
 * <p/>
 * This effect does not carry across sessions as the player is immediately unfrozen upon quitting
 * the game.
 */
public class Freeze extends CommonModule {

  private static final Potion NO_JUMP = Potions.noJump().infinite();

  private final Map<Player, PlayerData> frozenPlayers = new HashMap<>();
  private final Listener listener = new PlayerListener();
  private final TickerTask expiryTask;
  private final PotionEffectManager potionEffectManager;

  /**
   * Constructs a new {@link Freeze}. This is equivalent to calling {@link #Freeze(ModuleContainer,
   * long)} with the {@code long} parameter as 1.
   *
   * @param container module container to own this module.
   *
   * @see #Freeze(ModuleContainer, long)
   * @see #freeze(Player, int)
   */
  public Freeze(@Nonnull ModuleContainer container) {
    this(container, 1);
  }

  /**
   * Constructs a new {@link Freeze}. The long parameter is how often (in ticks) the expiry
   * checking task should run, by default it's 1.
   *
   * @param container module container to own this module
   * @param interval interval of the expiry checking task
   *
   * @see #freeze(Player, int)
   */
  public Freeze(@Nonnull ModuleContainer container, long interval) {
    super(container);
    this.expiryTask = new ExpiryTask(0, interval);
    PotionEffectManager pem = this.container.getModule(PotionEffectManager.class);
    this.potionEffectManager = pem == null ? new PotionEffectManager(container) : pem;
    if (pem == null) { // we created our own freeze.
      this.potionEffectManager.setState(State.ACTIVE);
    }
  }

  @Override
  public boolean setState(State state) throws UnsupportedOperationException {
    State old = this.state;
    boolean change = super.setState(state);
    if (change) {
      switch (state) {
        case PAUSED:
          this.expiryTask.pause();
          break;
        case ACTIVE:
          if (old == State.PAUSED) { // Only resume if it was previously paused
            this.expiryTask.resume();
          }
          this.plugin.registerEvents(this.listener);
          this.expiryTask.start();
          break;
        case STOPPED:
          HandlerList.unregisterAll(this.listener);
          this.expiryTask.stop();
          break;
      }
    }
    return change;
  }

  /**
   * Freezes a {@link Player}, completely stopping them from moving, infinitely. This is equivalent
   * to calling {@link #freeze(Player, int)} with the {@code long} parameter as -1.
   * <p />
   * This effect does not carry across sessions as the player is immediately unfrozen upon quitting
   * the game.
   *
   * @param player player to freeze
   */
  public void freeze(@Nonnull Player player) {
    freeze(player, -1);
  }

  /**
   * Freezes a {@link Player}, completely stopping them from moving. The duration is in ticks, if
   * set to anything smaller than 0, the effect will be infinite.
   * This is equivalent to calling {@link #freeze(Player, int, boolean)} with the {@code boolean}
   * parameter as false.
   * <p />
   * This effect does not carry across sessions as the player is immediately unfrozen upon quitting
   * the game.
   *
   * @param player player to freeze
   * @param duration duration (in ticks) of this effect
   */
  public void freeze(@Nonnull Player player, int duration) {
    freeze(player, duration, false);
  }

  /**
   * Freezes a {@link Player}, completely stopping them from moving. The duration is in ticks, if
   * set to anything smaller than 0, the effect will be infinite. If {@code turningAllowed} is
   * true, then the player may look around them, but still be frozen in their block coordinates.
   *
   * <p />
   * This effect does not carry across sessions as the player is immediately unfrozen upon quitting
   * the game.
   *
   * @param player player to freeze
   * @param duration duration (in ticks) of this effect
   * @param turningAllowed whether to allow the player to look around, but still not move
   */
  public void freeze(@Nonnull Player player, int duration, boolean turningAllowed) {
    PlayerData data = this.frozenPlayers.get(player);
    if (data != null) {
      data.setExpires(duration);
    }
    this.frozenPlayers.put(player, new PlayerData(player, duration, turningAllowed));
    this.potionEffectManager.apply(NO_JUMP, player);
    player.setWalkSpeed(0);
    player.setFlySpeed(0);
    player.setAllowFlight(true);
    player.setFlying(true);
  }

  /**
   * Unfreezes a {@link Player}, undoing any changes applied by this effect.
   *
   * @param player player to unfreeze
   *
   * @return whether the player was unfrozen
   */
  public boolean unfreeze(@Nonnull Player player) {
    return unfreeze(player, this.frozenPlayers.remove(player));
  }

  private boolean unfreeze(@Nonnull Player player, PlayerData data) {
    if (data != null) {
      data.applyDefaults(player);
      this.potionEffectManager.clear(player, NO_JUMP.getPotionId());
    }
    return data != null;
  }

  public boolean isFrozen(@Nonnull Player player) {
    return this.frozenPlayers.containsKey(player);
  }

  private final class ExpiryTask extends TickerTask {

    public ExpiryTask(long delay, long interval) {
      super(Freeze.this.plugin, delay, interval);
    }

    @Override
    public void run() {
      Iterator<Entry<Player, PlayerData>> it = Freeze.this.frozenPlayers.entrySet().iterator();
      while (it.hasNext()) {
        Entry<Player, PlayerData> entry = it.next();
        if (entry.getValue().isDone()) {
          unfreeze(entry.getKey(), entry.getValue());
          it.remove();
        }
      }
    }
  }

  private final class PlayerListener implements Listener {

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
      PlayerData data = Freeze.this.frozenPlayers.get(event.getPlayer());
      if (data != null && (!data.turningAllowed
                           || !LocationUtils.isSameCoordinates(event.getFrom(), event.getTo()))) {
        event.getPlayer().teleport(event.getFrom());
      }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
      unfreeze(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
      if (!event.isFlying() && Freeze.this.frozenPlayers.containsKey(event.getPlayer())) {
        event.setCancelled(true);
      }
    }
  }

  private final class PlayerData {

    // player's state prior to being frozen
    private final float walkSpeed;
    private final float flySpeed;
    private final boolean allowFlight;
    private final boolean flying;
    private final boolean turningAllowed;

    private long expires = -1;

    public PlayerData(Player player, int duration, boolean turningAllowed) {
      this.expires = duration;
      this.walkSpeed = player.getWalkSpeed();
      this.flySpeed = player.getFlySpeed();
      this.allowFlight = player.getAllowFlight();
      this.flying = player.isFlying();
      this.turningAllowed = turningAllowed;
      setExpires(duration);
    }

    public void applyDefaults(Player player) {
      player.setWalkSpeed(this.walkSpeed);
      player.setFlySpeed(this.flySpeed);
      player.setAllowFlight(this.allowFlight);
      player.setFlying(this.flying);
    }

    public boolean isDone() {
      return this.expires > -1 && System.currentTimeMillis() - this.expires >= 0;
    }

    public void setExpires(int duration) {
      if (duration < 0 || duration != Integer.MAX_VALUE) {
        this.expires = System.currentTimeMillis() + ((long) duration * 50);
      } else {
        this.expires = -1;
      }
    }
  }
}
