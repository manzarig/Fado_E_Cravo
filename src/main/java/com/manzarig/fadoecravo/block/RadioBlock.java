package com.manzarig.fadoecravo.block;

import com.manzarig.fadoecravo.RadioClientBridge;
import com.manzarig.fadoecravo.block.entity.RadioBlockEntity;
import com.manzarig.fadoecravo.registry.ModBlocks;
import com.manzarig.fadoecravo.registry.ModSounds;
import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The AM radio: a wooden cabinet with the dial on its front.
 *
 * <p>It has three states besides the facing. {@link #ON} says whether it is switched on and is what
 * the client listens to. {@link #POWERED} remembers whether a neighbour was giving it a redstone
 * signal, so that it can tell a new signal from one that was already there; it has no effect on
 * the model. Switching on or off happens in {@link #togglePower}, whether it is a sneaking player,
 * a screen or a redstone pulse that asks for it.
 *
 * <p>The frequency is not a block state: each of its 1071 values would multiply the number of block
 * states. It is in {@link RadioBlockEntity}.
 */
public class RadioBlock extends BaseEntityBlock {
	public static final MapCodec<RadioBlock> CODEC = simpleCodec(RadioBlock::new);
	/** The side the front of the cabinet, with the dial, faces; that is towards the player who placed it. */
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
	public static final BooleanProperty ON = BooleanProperty.create("on");
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	/** The cabinet of a radio facing north: x 1-15 and z 4-12 pixels as seen from above, so 14 x 8, centred on the block. */
	private static final Footprint CABINET_FACING_NORTH = new Footprint(1, 4, 15, 12);
	/** Weakest programme that gives off notes. */
	private static final double NOTE_MIN_LEVEL = 0.3;
	/** Chance per animation tick (about 20 a second near the player) of a note at full signal. */
	private static final float NOTE_CHANCE = 0.03f;
	/** The cabinet is 12 pixels high. */
	private static final double CABINET_HEIGHT = 12;
	/** The outline for each way the radio can face. Declared after the constants that {@link #cabinet} reads. */
	private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

	static {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			SHAPES.put(facing, cabinet(facing));
		}
	}

	public RadioBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any()
				.setValue(FACING, Direction.NORTH)
				.setValue(ON, false)
				.setValue(POWERED, false));
	}

	private static VoxelShape cabinet(Direction facing) {
		Footprint footprint = CABINET_FACING_NORTH.turnedTo(facing);
		return Block.box(footprint.minX(), 0, footprint.minZ(), footprint.maxX(), CABINET_HEIGHT, footprint.maxZ());
	}

	/**
	 * An area of the block seen from above, in pixels (a sixteenth of a block). It is a record of its
	 * own, apart from the block, so that the turning can be tested without a running game.
	 */
	record Footprint(double minX, double minZ, double maxX, double maxZ) {
		/**
		 * This footprint, which is the one of a radio facing north, turned about the centre of the
		 * block as far as a radio facing {@code facing} is turned: a quarter clockwise, seen from
		 * above, for each step from north to east to south to west, which is how the blockstate
		 * turns the model.
		 */
		Footprint turnedTo(Direction facing) {
			return switch (facing) {
				case NORTH -> this;
				case EAST -> new Footprint(16 - maxZ, minX, 16 - minZ, maxX);
				case SOUTH -> new Footprint(16 - maxX, 16 - maxZ, 16 - minX, 16 - minZ);
				case WEST -> new Footprint(minZ, 16 - maxX, maxZ, 16 - minX);
				default -> throw new IllegalArgumentException("Not a horizontal direction: " + facing);
			};
		}
	}

	/** The grey line under the item's name that says how to use the radio; {@code BlockItem} asks the block for it. */
	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(RadioTooltip.line());
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, ON, POWERED);
	}

	/**
	 * Faces the player and starts with {@link #POWERED} set to the signal that is already there, so
	 * that placing the radio next to a powered block does not count as a new signal.
	 */
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite())
				.setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES.get(state.getValue(FACING));
	}

	/**
	 * The radio is drawn by its GeckoLib block entity renderer, which animates the needle, the knobs,
	 * the speaker and the glow. The block model ({@code block/radio}) is only used for the item and
	 * the break particles.
	 */
	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.ENTITYBLOCK_ANIMATED;
	}

	/**
	 * Now and then a music note above a radio that is playing a programme, like a jukebox; more often
	 * the clearer the station. Client side only, through {@link RadioClientBridge#speakerLevel}.
	 */
	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!state.getValue(ON)) {
			return;
		}
		double speaker = RadioClientBridge.speakerLevel(pos);
		if (speaker < NOTE_MIN_LEVEL || random.nextFloat() > NOTE_CHANCE * speaker) {
			return;
		}
		Direction facing = state.getValue(FACING);
		double x = pos.getX() + 0.5 + facing.getStepX() * 0.3 + (random.nextDouble() - 0.5) * 0.4;
		double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.3 + (random.nextDouble() - 0.5) * 0.4;
		// For the note particle the x speed is its colour, from 0 to 1 along the note block palette.
		level.addParticle(ParticleTypes.NOTE, x, pos.getY() + 0.85, z, random.nextInt(25) / 24.0, 0.0, 0.0);
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new RadioBlockEntity(pos, state);
	}

	/**
	 * A plain use opens the radio screen, which exists on the client only; sneaking switches the
	 * radio on or off, which the server does. Both sides report success so that the click is
	 * consumed and the player's arm swings.
	 */
	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player.isSecondaryUseActive()) {
			if (!level.isClientSide) {
				togglePower(level, pos, state);
			}
		} else if (level.isClientSide) {
			RadioClientBridge.openScreen(pos);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	/**
	 * A redstone pulse switches the radio on or off, like a button on a note block. Only a signal
	 * that arrives counts, not one that goes away, and {@link #POWERED} is kept up to date either way.
	 */
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
		if (level.isClientSide) {
			return;
		}
		boolean powered = level.hasNeighborSignal(pos);
		if (powered == state.getValue(POWERED)) {
			return;
		}
		BlockState updated = state.setValue(POWERED, powered);
		if (powered) {
			togglePower(level, pos, updated);
		} else {
			level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
		}
	}

	/**
	 * Flips {@link #ON} on the server and plays the sound of it: a click and the valves warming up
	 * when the radio is switched on, a click when it is switched off. The one place that does it,
	 * for sneaking, the screen and redstone.
	 *
	 * @param state the current state of the radio at {@code pos}; the new state is derived from it
	 */
	public static void togglePower(Level level, BlockPos pos, BlockState state) {
		if (level.isClientSide || !state.is(ModBlocks.RADIO)) {
			return;
		}
		boolean switchOn = !state.getValue(ON);
		level.setBlock(pos, state.setValue(ON, switchOn), Block.UPDATE_CLIENTS);
		if (switchOn) {
			level.playSound(null, pos, ModSounds.POWER_ON, SoundSource.BLOCKS, 1.0f, 1.0f);
			level.playSound(null, pos, ModSounds.TUBE_WARMUP, SoundSource.BLOCKS, 1.0f, 1.0f);
		} else {
			level.playSound(null, pos, ModSounds.POWER_OFF, SoundSource.BLOCKS, 1.0f, 1.0f);
		}
	}
}
