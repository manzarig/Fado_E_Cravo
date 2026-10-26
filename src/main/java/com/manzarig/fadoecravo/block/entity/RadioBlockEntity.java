package com.manzarig.fadoecravo.block.entity;

import com.manzarig.fadoecravo.block.RadioBlock;
import com.manzarig.fadoecravo.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The state of one radio that is not part of its block state: the frequency its dial is set to.
 * Whether it is switched on lives in the block state ({@code RadioBlock.ON}).
 *
 * <p>The server owns the frequency. It changes it in response to a {@code SetFrequencyPayload} and
 * syncs it to nearby clients with the usual block entity update packet, which carries
 * {@link #getUpdateTag}.
 */
public class RadioBlockEntity extends BlockEntity {
	/** Lowest frequency of the medium wave band, in kHz. */
	public static final int MIN_KHZ = 530;
	/** Highest frequency of the medium wave band, in kHz. */
	public static final int MAX_KHZ = 1600;
	/** Frequency of a new radio, in kHz. It is the one of Rádio Tejo. */
	public static final int DEFAULT_KHZ = 1035;

	private static final String FREQUENCY_KEY = "frequency";

	/** How long the valves take to warm up after the radio is switched on, in ticks; as long as {@code sfx.tube_warmup}. */
	public static final int WARMUP_TICKS = 50;

	private int frequencyKhz = DEFAULT_KHZ;
	/**
	 * Client only, never saved: the game time at which this client saw the radio switched on, or
	 * {@link Long#MIN_VALUE} if it never did. A radio that was already on when it loaded is warm.
	 */
	private long switchedOnAt = Long.MIN_VALUE;

	public RadioBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.RADIO, pos, state);
	}

	/** Brings a frequency into the band, so that a bad value in a save file or a packet cannot break the radio. */
	public static int clampKhz(int khz) {
		return Math.max(MIN_KHZ, Math.min(MAX_KHZ, khz));
	}

	/** Whether {@code khz} is inside the band; the server rejects a packet that is not. */
	public static boolean isValidKhz(int khz) {
		return khz >= MIN_KHZ && khz <= MAX_KHZ;
	}

	public int getFrequencyKhz() {
		return frequencyKhz;
	}

	/**
	 * Tunes the radio. The value is clamped to the band. If it changes, the chunk is saved and the
	 * tracking clients get the new frequency.
	 */
	public void setFrequencyKhz(int khz) {
		int clamped = clampKhz(khz);
		if (clamped == frequencyKhz) {
			return;
		}
		frequencyKhz = clamped;
		setChanged();
		if (level != null) {
			BlockState state = getBlockState();
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
		}
	}

	/**
	 * Notices, on the client, the radio being switched on, which starts the warm-up. The chunk calls
	 * this whenever the block state at this position changes and the block entity stays.
	 */
	@Override
	@SuppressWarnings("deprecation")
	public void setBlockState(BlockState state) {
		BlockState old = getBlockState();
		super.setBlockState(state);
		if (level != null && level.isClientSide && !isOn(old) && isOn(state)) {
			switchedOnAt = level.getGameTime();
		}
	}

	/**
	 * Ticks of warm-up left at {@code gameTime}: from {@link #WARMUP_TICKS} right after the switch
	 * down to 0. Always 0 on the server and for a radio this client did not see switched on.
	 */
	public int warmupTicksLeft(long gameTime) {
		if (switchedOnAt == Long.MIN_VALUE || !isOn(getBlockState())) {
			return 0;
		}
		long since = gameTime - switchedOnAt;
		return since < 0 || since >= WARMUP_TICKS ? 0 : (int) (WARMUP_TICKS - since);
	}

	private static boolean isOn(BlockState state) {
		return state.hasProperty(RadioBlock.ON) && state.getValue(RadioBlock.ON);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt(FREQUENCY_KEY, frequencyKhz);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		frequencyKhz = tag.contains(FREQUENCY_KEY, Tag.TAG_ANY_NUMERIC) ? clampKhz(tag.getInt(FREQUENCY_KEY)) : DEFAULT_KHZ;
	}

	/** What a client needs to know when the chunk is sent: the same as is saved. */
	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
