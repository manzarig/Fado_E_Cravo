package com.manzarig.fadoecravo.block.entity;

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

	private int frequencyKhz = DEFAULT_KHZ;

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
