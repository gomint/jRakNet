package io.gomint.jraknet;

import io.gomint.jraknet.datastructures.TriadRange;
import io.gomint.jraknet.datastructures.TriadRangeList;
import org.junit.Test;

import java.util.Iterator;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class TriadRangeListTest {

	@Test
	public void testTriadRangeListAssembly() {
		TriadRangeList list = new TriadRangeList();
		Random random = new Random();

		final int NUM_ELEMENTS  = 2048;
		final int ELEMENT_RANGE = 2048;

		for ( int i = 0; i < NUM_ELEMENTS; ++i ) {
			list.insert( random.nextInt( ELEMENT_RANGE ) );
		}

		Iterator<TriadRange> it = list.iterator();
		int lastMax = -1;
		while ( it.hasNext() ) {
			TriadRange range = it.next();
			assertTrue( lastMax < range.getMin() );
			lastMax = range.getMax();
		}
	}

}
