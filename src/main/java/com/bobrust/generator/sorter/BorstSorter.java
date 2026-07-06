package com.bobrust.generator.sorter;

import java.util.*;
import java.util.stream.IntStream;

import com.bobrust.generator.BorstUtils;
import com.bobrust.util.data.AppConstants;

public class BorstSorter {
	private static final int MIN_SIZE = 8;
	// Average time for the sorting https://www.desmos.com/calculator/tjwldcg72h
	
	private static class Piece {
		final Blob blob;
		final int index;
		
		public Piece(Blob blob, int index) {
			this.blob = blob;
			this.index = index;
		}
	}
	
	private static class QTree {
		private final QTree[] nodes = new QTree[4];
		
		private final IntList list = new IntList();
		private final int x;
		private final int y;
		private final int s;
		private final int hs;
		
		public QTree(int width, int height) {
			this(0, 0, Math.max(width, height));
		}
		
		private QTree(int x, int y, int s) {
			this.x = x;
			this.y = y;
			this.s = s;
			this.hs = s / 2;
		}
		
		public void add_piece(Piece piece) {
			int x = piece.blob.x - this.x;
			int y = piece.blob.y - this.y;
			int r = piece.blob.size;
			
			if(hs < MIN_SIZE) {
				list.add(piece.index);
				return;
			}
			
			boolean x_min = x - r <= hs;
			boolean x_max = x + r >= hs;
			boolean y_min = y - r <= hs;
			boolean y_max = y + r >= hs;
			
			if(y_min) {
				if(x_min) add_piece(piece, 0);
				if(x_max) add_piece(piece, 1);
			}
			
			if(y_max) {
				if(x_min) add_piece(piece, 2);
				if(x_max) add_piece(piece, 3);
			}
		}
		
		private void add_piece(Piece piece, int index) {
			QTree node = nodes[index];
			if(node == null) {
				nodes[index] = (node = new QTree(x + (index & 1) * hs, y + (index >> 1) * hs, hs + (s & 1)));
			}
			
			node.add_piece(piece);
		}
		
		public IntList get_pieces(Piece piece) {
			IntList list = new IntList();
			get_pieces(piece, list);
			list.sort();
			
			IntList result = new IntList();
			int last = -1;
			for(int i = 0; i < list.size(); i++) {
				int value = list.get(i);
				
				// Skip all values outside of range
				if(value > piece.index) break;
				
				if(value != last) {
					result.add(value);
					last = value;
				}
			}
			
			return result;
		}
		
		private void get_pieces(Piece piece, IntList set) {
			int x = piece.blob.x - this.x;
			int y = piece.blob.y - this.y;
			int r = piece.blob.size;
			
			if(hs < MIN_SIZE) {
				// || (r >= hs && r <= s)) {
				for(int i = 0; i < list.size(); i++) {
					set.add(list.get(i));
				}
			}
			
			boolean x_min = x - r <= hs;
			boolean x_max = x + r >= hs;
			boolean y_min = y - r <= hs;
			boolean y_max = y + r >= hs;
			
			if(y_min) {
				if(x_min) get_pieces(piece, 0, set);
				if(x_max) get_pieces(piece, 1, set);
			}
			
			if(y_max) {
				if(x_min) get_pieces(piece, 2, set);
				if(x_max) get_pieces(piece, 3, set);
			}
		}
		
		private void get_pieces(Piece piece, int index, IntList set) {
			QTree node = nodes[index];
			if(node != null) {
				node.get_pieces(piece, set);
			}
		}
	}
	
	public static BlobList sort(BlobList data) {
		return sort(data, 512);
	}

	public static BlobList sort(BlobList data, int size) {
		long start = System.nanoTime();
		int len = data.size();
		List<Blob> blobs = new ArrayList<>();
		for (int i = 0; i < data.size(); i += AppConstants.MAX_SORT_GROUP) {
			Piece[] pieces = new Piece[Math.min(AppConstants.MAX_SORT_GROUP, len - i)];
			for (int j = 0; j < pieces.length; j++) {
				pieces[j] = new Piece(data.get(i + j), j);
			}
			IntList[] localMap = new IntList[pieces.length];
			blobs.addAll(Arrays.asList(sort0(pieces, size, localMap)));
		}

		BlobList result = new BlobList(blobs);

		if (AppConstants.DEBUG_TIME) {
			long time = System.nanoTime() - start;
			AppConstants.LOGGER.info("BorstSorter.sort(data, size) took {} ms for {} shapes", time / 1000000.0, data.size());
		}

		return result;
	}
	
	private static Blob[] sort0(Piece[] array, int size, IntList[] map) {
		Blob[] out = new Blob[array.length];
		out[0] = array[0].blob;
		array[0] = null;

		QTree tree = new QTree(size, size);
		/* Calculate the intersections */ {
			for(int i = 1; i < array.length; i++) {
				tree.add_piece(array[i]);
			}

			// Use the quad tree to efficiently calculate the collisions
			IntStream.range(1, array.length).parallel().forEach((i) -> {
				map[i] = get_intersections(array[i], array, tree);
				map[i].reverse();
			});
		}

		IntList[][] cache = create_cache(array);

		int start = 1;
		int i = 0;
		while(++i < array.length) {
			Blob last = out[i - 1];
			int index = find_best_fast_cache(last, start, cache, array, map);
			out[i] = array[index].blob;
			array[index] = null;

			// Make the starting point shift place
			if(index == start) {
				for(; start < array.length; start++) {
					if(array[start] != null) break;
				}
			}
		}

		return out;
	}
	
	// The painter pays one control click per size / color / alpha / shape
	// change between consecutive blobs, so the greedy next-blob cache is keyed
	// on all four dimensions (Q2 made alpha vary per blob; shape is future
	// proofing for P11): list_all = zero control changes, list_either = exactly
	// one. With a single alpha and shape in the data this degenerates to the
	// old (size, color) behavior.
	private static final int SHAPE_TYPES = 4; // Blob.shapeIndex domain: 0..3

	private static int cacheKey(int size, int color, int alpha, int shape) {
		final int sizeLen = BorstUtils.SIZES.length;
		final int alphaLen = BorstUtils.ALPHAS.length;
		return ((color * sizeLen + size) * alphaLen + alpha) * SHAPE_TYPES + shape;
	}

	// Takes 36 ms for 60000 shapes
	private static IntList[][] create_cache(Piece[] array) {
		final int colorLen = BorstUtils.COLORS.length;
		final int sizeLen = BorstUtils.SIZES.length;
		final int alphaLen = BorstUtils.ALPHAS.length;
		final int tableLen = colorLen * sizeLen * alphaLen * SHAPE_TYPES;
		IntList[] list_all = new IntList[tableLen];
		IntList[] list_either = new IntList[tableLen];

		for(Piece piece : array) {
			if(piece == null) continue;

			int color = piece.blob.colorIndex;
			int size = piece.blob.sizeIndex;
			int alpha = piece.blob.alphaIndex;
			int shape = piece.blob.shapeIndex;

			/* all — exact (size, color, alpha, shape) match */ {
				addToCache(list_all, cacheKey(size, color, alpha, shape), piece.index);
			}

			/* either — differs from the key in exactly one dimension */ {
				for(int j = 0; j < sizeLen; j++) {
					if(j != size) addToCache(list_either, cacheKey(j, color, alpha, shape), piece.index);
				}
				for(int j = 0; j < colorLen; j++) {
					if(j != color) addToCache(list_either, cacheKey(size, j, alpha, shape), piece.index);
				}
				for(int j = 0; j < alphaLen; j++) {
					if(j != alpha) addToCache(list_either, cacheKey(size, color, j, shape), piece.index);
				}
				for(int j = 0; j < SHAPE_TYPES; j++) {
					if(j != shape) addToCache(list_either, cacheKey(size, color, alpha, j), piece.index);
				}
			}
		}

		// Make sure we do not have any null values
		for(int i = 0; i < list_all.length; i++) {
			if(list_all[i] == null) list_all[i] = IntList.emptyList();
			if(list_either[i] == null) list_either[i] = IntList.emptyList();
		}

		return new IntList[][] { list_all, list_either };
	}

	private static void addToCache(IntList[] table, int key, int index) {
		IntList list = table[key];
		if(list == null) {
			table[key] = (list = new IntList());
		}

		list.add(index);
	}

	private static int find_best_fast_cache(Blob last, int first_non_null_index, IntList[][] cache, Piece[] array, IntList[] map) {
		final int key = cacheKey(last.sizeIndex, last.colorIndex, last.alphaIndex, last.shapeIndex);
		for(int type = 0; type < 2; type++) {
			IntList list = cache[type][key];
			for(int i = 0; i < list.size(); i++) {
				Piece p = array[list.get(i)];
				if(p == null) {
					// Remove elements from the list to ensure we remove memory
					list.remove(i--);
					continue;
				}
				
				IntList cols = map[p.index];
				while(!cols.isEmpty()) {
					if(array[cols.get(cols.size() - 1)] != null) {
						break;
					}
					
					cols.popLast();
				}
				
				if(!cols.isEmpty()) {
					continue;
				}
				
				// If we didn't have any collisions we return
				list.remove(i);
				return p.index;
			}
		}
		
		// If we didn't find any valid value we return the start because
		// that is the first non null value in the list.
		return first_non_null_index;
	}
	
	private static IntList get_intersections(Piece piece, Piece[] array, QTree tree) {
		IntList list = tree.get_pieces(piece);
		
		IntList result = null;
		Blob blob = piece.blob;
		int s2 = blob.size;
		for(int j = 0; j < list.size(); j++) {
			int i = list.get(j);
			
			Blob s = array[i].blob;
			int s1 = s.size;
			
			// If both the size and the color is equal of the two blobs
			// then they are indistinguishable from each other.
			if(s1 == s2 && s.color == blob.color) continue;
			
			int x = s.x - blob.x;
			int y = s.y - blob.y;
			int sum = s1 + s2;
			if(x * x + y * y < sum * sum) {
				if(result == null) {
					result = new IntList();
				}
				
				result.add(i);
			}
		}
		
		return result == null ? IntList.emptyList():result;
	}
}
