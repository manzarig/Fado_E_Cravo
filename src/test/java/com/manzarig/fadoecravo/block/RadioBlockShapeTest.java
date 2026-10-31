package com.manzarig.fadoecravo.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.manzarig.fadoecravo.block.RadioBlock.Footprint;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** How the cabinet of the radio is turned for each way it can face. */
class RadioBlockShapeTest {
	/** The cabinet of a radio facing north, as the block defines it: x 1-15, z 4-12. */
	private static final Footprint NORTH = new Footprint(1, 4, 15, 12);

	@Test
	void facingNorthChangesNothing() {
		assertEquals(NORTH, NORTH.turnedTo(Direction.NORTH));
	}

	@Test
	void facingSouthTurnsHalfwayAndTheCentredBoxLooksTheSame() {
		assertEquals(new Footprint(1, 4, 15, 12), NORTH.turnedTo(Direction.SOUTH));
	}

	@Test
	void facingEastOrWestSwapsWidthAndDepth() {
		assertEquals(new Footprint(4, 1, 12, 15), NORTH.turnedTo(Direction.EAST));
		assertEquals(new Footprint(4, 1, 12, 15), NORTH.turnedTo(Direction.WEST));
	}

	/** A box off the middle of the block shows which way each facing turns, as the centred cabinet cannot. */
	@Test
	void anOffCentreBoxTurnsClockwiseSeenFromAbove() {
		// A box against the north wall (z 0-4), where the front of a north-facing radio would be.
		Footprint atTheNorthWall = new Footprint(2, 0, 14, 4);
		assertEquals(new Footprint(12, 2, 16, 14), atTheNorthWall.turnedTo(Direction.EAST), "the east wall");
		assertEquals(new Footprint(2, 12, 14, 16), atTheNorthWall.turnedTo(Direction.SOUTH), "the south wall");
		assertEquals(new Footprint(0, 2, 4, 14), atTheNorthWall.turnedTo(Direction.WEST), "the west wall");
	}

	@Test
	void fourQuarterTurnsAreAFullTurn() {
		Footprint box = new Footprint(2, 0, 11, 4);
		Footprint turned = box.turnedTo(Direction.EAST).turnedTo(Direction.EAST)
				.turnedTo(Direction.EAST).turnedTo(Direction.EAST);
		assertEquals(box, turned);
	}

	@Test
	void everyFacingKeepsTheSizeOfTheBox() {
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			Footprint turned = NORTH.turnedTo(facing);
			boolean swapped = facing.getAxis() == Direction.Axis.X;
			double width = turned.maxX() - turned.minX();
			double depth = turned.maxZ() - turned.minZ();
			assertEquals(swapped ? 8 : 14, width, facing + " width");
			assertEquals(swapped ? 14 : 8, depth, facing + " depth");
		}
	}

	@Test
	void verticalDirectionsCannotBeTurnedTo() {
		assertThrows(IllegalArgumentException.class, () -> NORTH.turnedTo(Direction.UP));
	}
}
