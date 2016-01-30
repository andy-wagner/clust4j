package com.clust4j.algo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.commons.math3.linear.AbstractRealMatrix;
import org.apache.commons.math3.util.FastMath;

import com.clust4j.GlobalState;
import com.clust4j.TestSuite;
import com.clust4j.utils.QuadTup;
import com.clust4j.algo.preprocess.FeatureNormalization;
import com.clust4j.log.LogTimeFormatter;
import com.clust4j.log.Log.Tag.Algo;
import com.clust4j.utils.ClustUtils;
import com.clust4j.utils.Distance;
import com.clust4j.utils.DistanceMetric;
import com.clust4j.utils.EntryPair;
import com.clust4j.utils.GeometricallySeparable;
import com.clust4j.utils.Inequality;
import com.clust4j.utils.KDTree;
import com.clust4j.utils.MatUtils;
import com.clust4j.utils.MinkowskiDistance;
import com.clust4j.utils.MatUtils.MatSeries;
import com.clust4j.utils.ModelNotFitException;
import com.clust4j.utils.VecUtils;
import com.clust4j.utils.VecUtils.VecSeries;

/**
 * Hierarchical Density-Based Spatial Clustering of Applications with Noise. 
 * Performs {@link DBSCAN} over varying epsilon values and integrates the result to 
 * find a clustering that gives the best stability over epsilon. This allows 
 * HDBSCAN to find clusters of varying densities (unlike DBSCAN), and be more 
 * robust to parameter selection.
 * 
 * @author Taylor G Smith, adapted from the Python 
 * <a href="https://github.com/lmcinnes/hdbscan">HDBSCAN package</a>, inspired by
 * <a href="http://dl.acm.org/citation.cfm?id=2733381">the paper</a> by 
 * R. Campello, D. Moulavi, and J. Sander
 */
public class HDBSCAN extends AbstractDBSCAN {
	private static final long serialVersionUID = -5112901322434131541L;
	public static final Algorithm DEF_ALGO = Algorithm.GENERIC;
	public static final double DEF_ALPHA = 1.0;
	public static final boolean DEF_APPROX_MIN_SPAN = true;
	public static final int DEF_LEAF_SIZE = 40;
	public static final int DEF_MIN_CLUST_SIZE = 5;
	//public static final boolean DEF_GENERATE_MIN_SPAN = false;
	
	private final Algorithm algo;
	private final double alpha;
	private final boolean approxMinSpanTree;
	private final int min_cluster_size;
	private final int leafSize;
	//private final boolean genMinSpanTree;

	private volatile HDBSCANLinkageTree tree = null;
	private volatile double[][] dist_mat = null;
	private volatile int[] labels = null;
	private volatile int numClusters;
	private volatile int numNoisey;
	
	
	public static enum Algorithm {
		GENERIC,
		PRIMS_KD_TREE,
		PRIMS_BALLTREE,
		BORUVKA_KDTREE,
		BORUVKA_BALLTREE
	}
	
	
	
	public final static ArrayList<Class<? extends GeometricallySeparable>> ValidKDMetrics;
	static {
		ValidKDMetrics = new ArrayList<>();
		ValidKDMetrics.add(Distance.EUCLIDEAN.getClass());
		ValidKDMetrics.add(Distance.MANHATTAN.getClass());
		ValidKDMetrics.add(MinkowskiDistance.class);
		ValidKDMetrics.add(Distance.CHEBYSHEV.getClass());
	}
	
	
	/**
	 * Constructs an instance of HDBSCAN from the default values
	 * @param data
	 */
	public HDBSCAN(final AbstractRealMatrix data) {
		this(data, DEF_MIN_PTS);
	}
	
	/**
	 * Constructs an instance of HDBSCAN from the default values
	 * @param eps
	 * @param data
	 */
	public HDBSCAN(final AbstractRealMatrix data, final int minPts) {
		this(data, new HDBSCANPlanner(minPts));
	}
	
	/**
	 * Constructs an instance of HDBSCAN from the provided builder
	 * @param builder
	 * @param data
	 */
	public HDBSCAN(final AbstractRealMatrix data, final HDBSCANPlanner planner) {
		super(data, planner);
		this.algo = planner.algo;
		this.alpha = planner.alpha;
		this.approxMinSpanTree = planner.approxMinSpanTree;
		this.min_cluster_size = planner.min_cluster_size;
		this.leafSize = planner.leafSize;
		//this.genMinSpanTree = planner.genMinTree;
		
		if(alpha == 0.0)
			throw new ArithmeticException("alpha cannot equal 0");
		
		
		if(!algo.equals(Algorithm.GENERIC) && !(planner.getSep() instanceof DistanceMetric)) {
			warn("algorithms leveraging NearestNeighborHeapSearch require a DistanceMetric; "
					+ "using default Euclidean distance");
			setSeparabilityMetric(DEF_DIST);
		}
		
		
		meta("min_cluster_size="+min_cluster_size);
		meta("min_pts="+planner.minPts);
		meta("algorithm="+algo);
		meta("alpha="+alpha);
	}
	
	
	
	/**
	 * A builder class to provide an easier constructing
	 * interface to set custom parameters for HDBSCAN
	 * @author Taylor G Smith
	 */
	final public static class HDBSCANPlanner extends AbstractDBSCANPlanner {
		private int minPts = DEF_MIN_PTS;
		private boolean scale = DEF_SCALE;
		private GeometricallySeparable dist	= DEF_DIST;
		private boolean verbose	= DEF_VERBOSE;
		private Random seed = DEF_SEED;
		private FeatureNormalization norm = DEF_NORMALIZER;
		private Algorithm algo = DEF_ALGO;
		private double alpha = DEF_ALPHA;
		private boolean approxMinSpanTree = DEF_APPROX_MIN_SPAN;
		private int min_cluster_size = DEF_MIN_CLUST_SIZE;
		private int leafSize = DEF_LEAF_SIZE;
		//private boolean genMinTree = DEF_GENERATE_MIN_SPAN;
		
		
		public HDBSCANPlanner() { }
		public HDBSCANPlanner(final int minPts) {
			this.minPts = minPts;
		}

		
		@Override
		public HDBSCAN buildNewModelInstance(AbstractRealMatrix data) {
			return new HDBSCAN(data, this);
		}
		
		@Override
		public HDBSCANPlanner copy() {
			return new HDBSCANPlanner(minPts)
				.setAlgo(algo)
				.setAlpha(alpha)
				.setApprox(approxMinSpanTree)
				.setLeafSize(leafSize)
				.setMinClustSize(min_cluster_size)
				//.setGenMinSpan(genMinTree)
				.setScale(scale)
				.setSep(dist)
				.setSeed(seed)
				.setVerbose(verbose)
				.setNormalizer(norm);
		}

		@Override
		public int getMinPts() {
			return minPts;
		}
		
		@Override
		public GeometricallySeparable getSep() {
			return dist;
		}
		
		@Override
		public boolean getScale() {
			return scale;
		}
		
		@Override
		public Random getSeed() {
			return seed;
		}
		
		@Override
		public boolean getVerbose() {
			return verbose;
		}
		
		public HDBSCANPlanner setAlgo(final Algorithm algo) {
			this.algo = algo;
			return this;
		}
		
		public HDBSCANPlanner setAlpha(final double a) {
			this.alpha = a;
			return this;
		}
		
		public HDBSCANPlanner setApprox(final boolean b) {
			this.approxMinSpanTree = b;
			return this;
		}
		
		public HDBSCANPlanner setLeafSize(final int leafSize) {
			this.leafSize = leafSize;
			return this;
		}
		
		public HDBSCANPlanner setMinClustSize(final int min) {
			this.min_cluster_size = min;
			return this;
		}
		
		/*public HDBSCANPlanner setGenMinSpan(final boolean b) {
			this.genMinTree = b;
			return this;
		}*/
		
		@Override
		public HDBSCANPlanner setMinPts(final int minPts) {
			this.minPts = minPts;
			return this;
		}
		
		@Override
		public HDBSCANPlanner setScale(final boolean scale) {
			this.scale = scale;
			return this;
		}
		
		@Override
		public HDBSCANPlanner setSeed(final Random seed) {
			this.seed = seed;
			return this;
		}
		
		@Override
		public HDBSCANPlanner setSep(final GeometricallySeparable dist) {
			this.dist = dist;
			return this;
		}
		
		public HDBSCANPlanner setVerbose(final boolean v) {
			this.verbose = v;
			return this;
		}
		
		@Override
		public FeatureNormalization getNormalizer() {
			return norm;
		}
		
		@Override
		public HDBSCANPlanner setNormalizer(FeatureNormalization norm) {
			this.norm = norm;
			return this;
		}
	}
	
	
	
	
	
	/**
	 * A simple extension of {@link ArrayList} that also provides
	 * a simple heap/stack interface of methods.
	 * @author Taylor G Smith
	 * @param <T>
	 */
	final static class HList<T> extends ArrayList<T> {
		private static final long serialVersionUID = 2784009809720305029L;
		
		public HList() {
			super();
		}
		
		public HList(Collection<? extends T> coll) {
			super(coll);
		}
		
		public HList(T[] t) {
			super(t.length);
			for(T tee: t)
				add(tee);
		}
		
		public T pop() {
			if(this.isEmpty())
				return null;
			
			final T t = this.get(this.size() - 1);
			this.remove(this.size() - 1);
			return t;
		}
		
		public void push(T t) {
			if(this.isEmpty())
				add(t);
			else
				add(0, t);
		}
	}
	
	
	/** Classes that will explicitly need to define 
	 *  reachability will have to implement this interface */
	interface ExplicitMutualReachability { double[][] mutualReachability(); }
	interface Boruvka extends ExplicitMutualReachability {}
	interface Prim {}
	
	
	/**
	 * Util mst linkage methods
	 * @author Taylor G Smith
	 */
	static class LinkageTreeUtils {	
		
		/**
		 * Perform a breadth first search on a tree
		 * @param hierarchy
		 * @param root
		 * @return
		 */
		// Tested: passing
		static HList<Integer> breadthFirstSearch(final double[][] hierarchy, final int root) {
			HList<Integer> toProcess = new HList<>(), tmp;
			int dim = hierarchy.length, maxNode = 2*dim, numPoints = maxNode - dim+1;
			
			toProcess.add(root);
			HList<Integer> result = new HList<>();
			while(!toProcess.isEmpty()) {
				result.addAll(toProcess);
				
				tmp = new HList<>();
				for(Integer x: toProcess)
					if(x >= numPoints)
						tmp.add(x - numPoints);
				toProcess = tmp;
				
				tmp = new HList<>();
				if(!toProcess.isEmpty()) {
					for(Integer row: toProcess)
						for(int i = 0; i < 2; i++)
							tmp.add((int) hierarchy[row][wraparoundIdxGet(hierarchy[row].length, i)]);
					
					toProcess = tmp;
				}
			}
			
			return result;
		}
		
		// Tested: passing
		static TreeMap<Integer, Double> computeStability(HList<QuadTup<Integer, Integer, Double, Integer>> condensed) {
			double[] resultArr, births, lambdas = new double[condensed.size()];
			int[] sizes = new int[condensed.size()], parents = new int[condensed.size()];
			int child, parent, childSize, resultIdx, currentChild = -1, idx = 0, row = 0;
			double lambda, minLambda = 0;
			
			
			
			// ['parent', 'child', 'lambda', 'childSize']
			// Calculate starting maxes/mins
			int largestChild = Integer.MIN_VALUE,
				minParent = Integer.MAX_VALUE,
				maxParent = Integer.MIN_VALUE;
			
			int[] sortedChildren= new int[condensed.size()];
			double[] sortedLambdas = new double[condensed.size()];
			
			for(QuadTup<Integer, Integer, Double, Integer> q: condensed) {
				parent= q.one;
				child = q.two;
				lambda= q.three;
				childSize= q.four;
				
				if(child > largestChild)
					largestChild = child;
				if(parent < minParent)
					minParent = parent;
				if(parent > maxParent)
					maxParent = parent;
				
				parents[idx] = parent;
				sizes[idx]= childSize;
				lambdas[idx]= lambda;
				
				sortedChildren[idx] = child;
				sortedLambdas[idx++]= lambda;
			}
			
			int numClusters = maxParent - minParent + 1;
			births = VecUtils.rep(Double.NaN, largestChild + 1);
			Arrays.sort(sortedChildren);
			Arrays.sort(sortedLambdas);
			
			// Start first loop
			for(row = 0; row < sortedChildren.length; row++) {
				child = sortedChildren[row]; // 0,1,2 in test
				lambda= sortedLambdas[row];  // 1.667 in test
				
				if(child == currentChild)
					minLambda = FastMath.min(minLambda, lambda);
				else if(currentChild != -1) {
					// Already been initialized
					births[currentChild] = minLambda;
					currentChild = child;
					minLambda = lambda;
				} else {
					// Initialize
					currentChild = child;
					minLambda = lambda;
				}
			}

			resultArr = new double[numClusters];
			
			
			// Second loop
			double birthParent;
			for(idx = 0; idx < condensed.size(); idx++) {
				parent = parents[idx];
				lambda = lambdas[idx];
				childSize= sizes[idx];
				resultIdx = parent - minParent;
				
				// the Cython exploits the C contiguous pointer array's
				// out of bounds allowance (2.12325E-314), but we have to
				// do a check for that...
				birthParent = parent >= births.length ? GlobalState.Mathematics.TINY : births[parent];
				resultArr[resultIdx] += (lambda - birthParent) * childSize;
			}
			
			
			double[] top = VecUtils.asDouble(VecUtils.arange(minParent, maxParent + 1));
			double[][] mat = MatUtils.transpose(VecUtils.vstack(top, resultArr));
			
			TreeMap<Integer, Double> result = new TreeMap<>();
			for(idx = 0; idx < mat.length; idx++)
				result.put( (int)mat[idx][0], mat[idx][1]);
			
			return result;
		}
		
		// Tested: passing
		static HList<QuadTup<Integer, Integer, Double, Integer>> condenseTree(final double[][] hierarchy, final int minSize) {
			final int m = hierarchy.length;
			int root = 2 * m, numPoints = root/2 + 1 /*Integer division*/, nextLabel = numPoints+1;
			HList<Integer> nodeList = breadthFirstSearch(hierarchy, root), tmpList;
			HList<QuadTup<Integer, Integer, Double, Integer>> resultList = new HList<>();
			int[] relabel = new int[nodeList.size()]; 
			boolean[] ignore = new boolean[nodeList.size()];
			double[] children;
			
			double lambda;
			int left, right, leftCount, rightCount;
			relabel[root] = numPoints;
			
			
			
			for(Integer node: nodeList) {
				
				if(ignore[node] || node < numPoints)
					continue;
				
				children = hierarchy[wraparoundIdxGet(hierarchy.length, node-numPoints)];
				left = (int) children[0];
				right= (int) children[1];
				
				if(children[2] > 0)
					lambda = 1.0 / children[2];
				else lambda = Double.POSITIVE_INFINITY;
				
				if(left >= numPoints)
					leftCount = (int) (hierarchy[wraparoundIdxGet(hierarchy.length, left-numPoints)][3]);
				else leftCount = 1;
				
				if(right >= numPoints)
					rightCount = (int)(hierarchy[wraparoundIdxGet(hierarchy.length,right-numPoints)][3]);
				else rightCount = 1;
				
				
				
				if(leftCount >= minSize && rightCount >= minSize) {
					relabel[left] = nextLabel++;
					resultList.add(new QuadTup<Integer, Integer, Double, Integer>(
						relabel[wraparoundIdxGet(relabel.length, node)],
						relabel[wraparoundIdxGet(relabel.length, left)],
						lambda, leftCount ));
					
					relabel[wraparoundIdxGet(relabel.length, right)] = nextLabel++;
					resultList.add(new QuadTup<Integer, Integer, Double, Integer>(
						relabel[wraparoundIdxGet(relabel.length, node)],
						relabel[wraparoundIdxGet(relabel.length,right)],
						lambda, rightCount ));
					
					
				} else if(leftCount < minSize && rightCount < minSize) {
					tmpList = breadthFirstSearch(hierarchy, left);
					for(Integer subnode: tmpList) {
						if(subnode < numPoints)
							resultList.add(new QuadTup<Integer, Integer, Double, Integer>(
								relabel[wraparoundIdxGet(relabel.length, node)], subnode,
								lambda, 1));
						ignore[subnode] = true;
					}
					
					tmpList = breadthFirstSearch(hierarchy, right);
					for(Integer subnode: tmpList) {
						if(subnode < numPoints)
							resultList.add(new QuadTup<Integer, Integer, Double, Integer>(
								relabel[wraparoundIdxGet(relabel.length, node)], subnode,
								lambda, 1));
						ignore[subnode] = true;
					}
					
					
 				} else if(leftCount < minSize) {
 					relabel[right] = relabel[node];
 					tmpList = breadthFirstSearch(hierarchy, left);
 					
 					for(Integer subnode: tmpList) {
						if(subnode < numPoints)
							resultList.add(new QuadTup<Integer, Integer, Double, Integer>(
								relabel[wraparoundIdxGet(relabel.length, node)], subnode,
								lambda, 1));
						ignore[subnode] = true;
					}
 				}
				
				
 				else {
 					relabel[left] = relabel[node];
 					tmpList = breadthFirstSearch(hierarchy, right);
 					for(Integer subnode: tmpList) {
						if(subnode < numPoints)
							resultList.add(new QuadTup<Integer, Integer, Double, Integer>(
								relabel[wraparoundIdxGet(relabel.length, node)], subnode,
								lambda, 1));
						ignore[subnode] = true;
					}
 				}
			}
			
			return resultList;
		}
		
		/**
		 * Generic linkage core method
		 * @param X
		 * @param m
		 * @return
		 */
		static double[][] mstLinkageCore(final double[][] X, final int m) { // Tested: passing
			int[] node_labels, current_labels, tmp_labels; 
			double[] current_distances, left, right;
			boolean[] label_filter;
			boolean val;
			int current_node, new_node_index, new_node, i, j, trueCt, idx;
			VecSeries series;
			
			double[][] result = new double[m-1][3];
			node_labels = VecUtils.arange(m);
			current_node = 0;
			current_distances = VecUtils.rep(Double.POSITIVE_INFINITY, m);
			current_labels = node_labels;
			
			
			
			for(i = 1; i < node_labels.length; i++) {
				
				// Create the boolean mask; takes 2N to create mask and then filter
				// however, creating the left vector concurrently 
				// trims off one N pass. This could be done using Vector.VecSeries
				// but that would add an extra pass of N
				idx = 0;
				trueCt = 0;
				label_filter = new boolean[current_labels.length];
				for(j = 0; j < label_filter.length; j++) {
					val = current_labels[j] != current_node;
					if(val)
						trueCt++;
					
					label_filter[j] = val;
				}
				
				tmp_labels = new int[trueCt];
				left = new double[trueCt];
				for(j = 0; j < current_labels.length; j++) {
					if(label_filter[j]) {
						tmp_labels[idx] = current_labels[j];
						left[idx] = current_distances[j];
						idx++;
					}
				}
				
				current_labels = tmp_labels;
				right = new double[current_labels.length];
				for(j = 0; j < right.length; j++)
					right[j] = X[current_node][current_labels[j]];
				
				// Build the current_distances vector
				series = new VecSeries(left, Inequality.LT, right);
				current_distances = VecUtils.where(series, left, right);
				
				
				// Get next iter values
				new_node_index = VecUtils.argMin(current_distances);
				new_node = current_labels[new_node_index];
				result[i-1][0] = (double)current_node;
				result[i-1][1] = (double)new_node;
				result[i-1][2] = current_distances[new_node_index];
				
				current_node = new_node;
			}
			
			return result;
		}
		
		static double[][] mstLinkageCore_cdist(final double[][] raw, final double[] coreDistances, GeometricallySeparable sep, final double alpha) {
			double[] currentDists;
			int[] inTreeArr;
			double[][] resultArr;
			
			int currentNode = 0, newNode, i, j, dim = raw.length;
			double currentNodeCoreDist, rightVal, leftVal, coreVal, newDist;
			
			resultArr = new double[dim - 1][3];
			inTreeArr = new int[dim];
			currentDists = VecUtils.rep(Double.POSITIVE_INFINITY, dim);
			
			
			for(i = 1; i < dim; i++) {
				inTreeArr[currentNode] = 1;
				currentNodeCoreDist = coreDistances[currentNode];
				
				newDist = Double.MAX_VALUE;
				newNode = 0;
				
				for(j = 0; j < dim; j++) {
					if(inTreeArr[j] != 0)
						continue; // only skips currentNode idx
					
					rightVal = currentDists[j];
					leftVal = sep.getDistance(raw[currentNode], raw[j]);
					
					if(alpha != 1.0)
						leftVal /= alpha;
					
					coreVal = coreDistances[j];
					if(currentNodeCoreDist > rightVal || coreVal > rightVal
						|| leftVal > rightVal) {
						if(rightVal < newDist) { // Should always be the case?
							newDist = rightVal;
							newNode = j;
						}
						
						continue;
					}
					
					
					if(coreVal > currentNodeCoreDist) {
						if(coreVal > leftVal)
							leftVal = coreVal;
					} else {
						if(currentNodeCoreDist > leftVal)
							leftVal = currentNodeCoreDist;
					}
					
					
					if(leftVal < rightVal) {
						currentDists[j] = leftVal;
						if(leftVal < newDist) {
							newDist = leftVal;
							newNode = j;
						}
					} else {
						if(rightVal < newDist) {
							newDist = rightVal;
							newNode = j;
						}
					}
				} // end for j
				
				resultArr[i - 1][0] = currentNode;
				resultArr[i - 1][1] = newNode;
				resultArr[i - 1][2] = newDist;
				currentNode = newNode;
			} // end for i
			
			
			return resultArr;
		}
		

		
		/**
		 * The index may be -1; this will return 
		 * the index of the length of the array minus
		 * the absolute value of the index in the case
		 * of negative indices, like the original Python
		 * code.
		 * @param array
		 * @param idx
		 * @throws ArrayIndexOutOfBoundsException if the absolute value of the index
		 * exceeds the length of the array
		 * @return the index to be queried in wrap-around indexing
		 */
		static int wraparoundIdxGet(int array_len, int idx) {
			int abs;
			if((abs = FastMath.abs(idx)) > array_len)
				throw new ArrayIndexOutOfBoundsException(idx);
			if(idx >= 0)
				return idx;
			return array_len - abs;
		}
	}
	
	abstract class HDBSCANLinkageTree {
		final HDBSCAN model;
		final GeometricallySeparable metric;
		final int m, n;
		
		HDBSCANLinkageTree() {
			model = HDBSCAN.this;
			metric = model.getSeparabilityMetric();
			m = model.data.getRowDimension();
			n = model.data.getColumnDimension();
			
			// Can only happen if this class is instantiated
			// from an already-trained HDBSCAN instance
			// if(null == dist_mat)
			//	  throw new IllegalStateException("null distance matrix");
		}
		
		abstract double[][] link();
	}
	
	abstract class KDTreeAlgorithm extends HDBSCANLinkageTree {
		int leafSize;
		
		KDTreeAlgorithm(int leafSize) {
			super();
			
			this.leafSize = leafSize;
			Class<? extends GeometricallySeparable> clz = 
				model.getSeparabilityMetric().getClass();
			if( !ValidKDMetrics.contains(clz) ) {
				warn(clz+" is not a valid distance metric for KDTrees. Valid metrics: " + ValidKDMetrics);
				warn("falling back to default metric: " + AbstractClusterer.DEF_DIST);
				model.setSeparabilityMetric(AbstractClusterer.DEF_DIST);
			}
		}
	}
	
	abstract class BallTreeAlgorithm extends HDBSCANLinkageTree {
		BallTreeAlgorithm() {
			super();
		}
	}
	
	/**
	 * Generic single linkage tree
	 * @author Taylor G Smith
	 */
	class GenericTree extends HDBSCANLinkageTree implements ExplicitMutualReachability {
		GenericTree() {
			super();
		}
		
		@Override
		double[][] link() {
			long s = System.currentTimeMillis();
			dist_mat = ClustUtils.distanceUpperTriangMatrix(data, getSeparabilityMetric());
			info("completed distance matrix computation in " + 
					LogTimeFormatter.millis(System.currentTimeMillis()-s, false) + 
					System.lineSeparator());
			
			final double[][] mutual_reachability = mutualReachability();
			double[][] min_spanning_tree = LinkageTreeUtils.mstLinkageCore(mutual_reachability, m);
			
			// Sort edges of the min_spanning_tree by weight
			min_spanning_tree = MatUtils.sortAscByCol(min_spanning_tree, 2);
			return label(min_spanning_tree);
		}
		
		@Override
		public double[][] mutualReachability() { // Tested: passing
			final int min_points = FastMath.min(m - 1, minPts);
			final double[] core_distances = MatUtils
				.partitionByRow(dist_mat, min_points)[min_points];
			
			if(alpha != 1.0)
				dist_mat = MatUtils.scalarDivide(dist_mat, alpha);
			
			
			final MatSeries ser1 = new MatSeries(core_distances, Inequality.GT, dist_mat);
			double[][] stage1 = MatUtils.where(ser1, core_distances, dist_mat);
			
			stage1 = MatUtils.transpose(stage1);
			final MatSeries ser2 = new MatSeries(core_distances, Inequality.GT, stage1);
			final double[][] result = MatUtils.where(ser2, core_distances, stage1);
			
			return MatUtils.transpose(result);
		}
	}
	
	/**
	 * Mutual reachability is implicit when using 
	 * {@link LinkageTreeUtils#mstLinkageCore_cdist},
	 * thus we don't need this class to implement 
	 * {@link ExplicitMutualReachability#mutualReachability()}
	 */
	class PrimsKDTree extends KDTreeAlgorithm implements Prim {
		PrimsKDTree(int leafSize) {
			super(leafSize);
		}
		
		@Override
		double[][] link() {
			final int min_points = FastMath.min(m - 1, minPts);
			final double[][] dt = data.getData();
			
			// We can safely cast the sep metric as DistanceMetric
			// after the check in the constructor
			KDTree tree = new KDTree(data, leafSize, 
				(DistanceMetric)model.getSeparabilityMetric(), model);
			
			// Query for dists to k nearest neighbors
			EntryPair<double[][], int[][]> query = tree.query(dt, min_points, true, true, true);
			double[][] dists = query.getKey();
			double[] coreDistances = MatUtils.getColumn(dists, dists[0].length - 1);
			
			double[][] minSpanningTree = LinkageTreeUtils
					.mstLinkageCore_cdist(dt, coreDistances, 
							getSeparabilityMetric(), alpha);
			
			return label(MatUtils.sortAscByCol(minSpanningTree, 2));
		}
	}
	
	/**
	 * Mutual reachability is implicit when using 
	 * {@link LinkageTreeUtils#mstLinkageCore_cdist},
	 * thus we don't need this class to implement 
	 * {@link ExplicitMutualReachability#mutualReachability()}
	 */
	class PrimsBallTree extends BallTreeAlgorithm implements Prim {
		PrimsBallTree() {
			super();
		}

		@Override
		double[][] link() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	class BoruvkaKDTree extends KDTreeAlgorithm implements Boruvka {
		BoruvkaKDTree(int leafSize) {
			super(leafSize);
		}

		@Override
		double[][] link() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public double[][] mutualReachability() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	class BoruvkaBallTree extends BallTreeAlgorithm implements Boruvka {
		BoruvkaBallTree() {
			super();
		}

		@Override
		double[][] link() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public double[][] mutualReachability() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	interface UnifiedFinder {
		void union(int m, int n);
		int find(int x);
	}
	
	static class TreeUnifyFind implements UnifiedFinder {
		int size;
		int [][] dataArr;
		int [] is_component;
		
		public TreeUnifyFind(int size) {
			dataArr = new int[size][2];
			// First col should be arange to size
			for(int i = 0; i < size; i++)
				dataArr[i][0] = i;
			
			is_component = VecUtils.repInt(1, size);
			this.size= size;
		}
		
		@Override
		public void union(int x, int y) {
			int x_root = find(x);
			int y_root = find(y);
			
			int x1idx = LinkageTreeUtils.wraparoundIdxGet(size, x_root);
			int y1idx = LinkageTreeUtils.wraparoundIdxGet(size, x_root);
			
			int dx1 = dataArr[x1idx][1];
			int dy1 = dataArr[y1idx][1];
			
			if(dx1 < dy1)
				dataArr[x1idx][0] = y_root;
			else if(dx1 > dy1)
				dataArr[y1idx][0] = x_root;
			else {
				dataArr[y1idx][0] = x_root;
				dataArr[x1idx][1] += 1;
			}
		}
		
		@Override
		public int find(int x) {
			final int idx = LinkageTreeUtils.wraparoundIdxGet(size, x);
			if(dataArr[idx][0] != x) {
				dataArr[idx][0] = find(dataArr[idx][0]);
				is_component[idx] = 0;
			}
			
			return dataArr[idx][0];
		}
		
		/**
		 * Returns all non-zero indices in is_component
		 * @return
		 */
		int[] components() {
			final HList<Integer> h = new HList<>();
			for(int i: is_component)
				if(i == 1)
					h.add(i);
			
			int idx = 0;
			int[] out = new int[h.size()];
			for(Integer i: h)
				out[idx++] = i;
			
			return out;
		}
	}
	
	static class UnifyFind implements UnifiedFinder {
		int [] parentArr, sizeArr, parent, size;
		int nextLabel;
		
		public UnifyFind(int N) {
			parentArr = VecUtils.repInt(-1, 2 * N - 1);
			nextLabel = N;
			sizeArr = VecUtils.cat(
					VecUtils.repInt(1, N), 
					VecUtils.repInt(0, N-1));
			
			parent = parentArr;
			size = sizeArr;
		}
		
		int fastFind(int n) {
			int p = n, tmp;
			while(parentArr[n] != -1)
				n = parentArr[n];
			
			while(parentArr[LinkageTreeUtils.wraparoundIdxGet(parentArr.length, p)] != n) {
				tmp = parentArr[LinkageTreeUtils.wraparoundIdxGet(parentArr.length,p)];
				
				parentArr[LinkageTreeUtils.wraparoundIdxGet(parentArr.length, p)] = n;
				p = tmp;
			}
			
			return n;
		}
		
		@Override
		public int find(int n) {
			while(parent[n] != -1)
				n = parent[n];
			return n;
		}
		
		@Override
		public void union(final int m, final int n) {
			size[nextLabel] = size[m] + size[n];
			parent[m] = nextLabel;
			parent[n] = nextLabel;
			size[nextLabel] = size[m] + size[n];
			nextLabel++;
		}
		
		@Override
		public String toString() {
			return "Parent arr: " + Arrays.toString(parentArr) + "; " +
					"Sizes: " + Arrays.toString(sizeArr) + "; " +
					"Parent: " + Arrays.toString(parent);
		}
	}
	
	

	


	protected static int[] doLabeling(HList<QuadTup<Integer, Integer, Double, Integer>> tree,
			HList<Integer> clusters, TreeMap<Integer, Integer> clusterMap) {
		
		QuadTup<Integer, Integer, Double, Integer> quad;
		int rootCluster, parent, child, n = tree.size(), cluster, i;
		int[] resultArr, parentArr = new int[n], childArr = new int[n];
		UnifiedFinder unionFind;
		
		// [parent, child, lambda, size]
		int maxParent = Integer.MIN_VALUE;
		int minParent = Integer.MAX_VALUE;
		for(i = 0; i < n; i++) {
			quad = tree.get(i);
			parentArr[i]= quad.one;
			childArr[i] = quad.two;
			
			if(quad.one < minParent)
				minParent = quad.one;
			if(quad.one > maxParent)
				maxParent = quad.one;
		}
		
		rootCluster = minParent;
		resultArr = new int[rootCluster];
		unionFind = new TreeUnifyFind(maxParent + 1);
		
		for(i = 0; i < n; i++) {
			child = childArr[i];
			parent= parentArr[i];
			if(!clusters.contains(child))
				unionFind.union(parent, child);
		}
		
		for(i = 0; i < rootCluster; i++) {
			cluster = unionFind.find(i);
			if(cluster <= rootCluster)
				resultArr[i] = NOISE_CLASS;
			else
				resultArr[i] = clusterMap.get(cluster);
		}
		
		return resultArr;
	}
	
	@Override
	public HDBSCAN fit() {
		synchronized(this) {
			
			try {
				if(null!=labels) // Then we've already fit this...
					return this;
				
				// First get the dist matrix
				final long start = System.currentTimeMillis();
				info("fitting model");
				//dist_mat = ClustUtils.distanceUpperTriangMatrix(data, getSeparabilityMetric());
				
				
				// Build the tree
				String msg = "constructing HDBSCAN single linkage dendrogram: ";
				Class<? extends HDBSCANLinkageTree> clz = null;
				switch(algo) {
					case GENERIC:
						clz = GenericTree.class;
						info(msg + clz.getName());
						tree = new GenericTree();
						break;
					case PRIMS_KD_TREE:
						clz = PrimsKDTree.class;
						info(msg + clz.getName());
						tree = new PrimsKDTree(leafSize);
						break;
					default:
						throw new InternalError("illegal algorithm");
				}
				
				long treeStart = System.currentTimeMillis();
				final double[][] build = tree.link();
				info("completed tree building in " + 
						LogTimeFormatter.millis(System.currentTimeMillis()-treeStart, false) + 
						System.lineSeparator());
				
				
				info("Labeling clusters");
				labels = treeToLabels(data.getData(), build, min_cluster_size);
				
				
				info("model "+getKey()+" completed in " + 
						LogTimeFormatter.millis(System.currentTimeMillis()-start, false) + 
						System.lineSeparator());
				
				dist_mat = null;
				tree = null;
				
				return this;
			} catch(OutOfMemoryError | StackOverflowError e) {
				error(e.getLocalizedMessage() + " - ran out of memory during model fitting");
				throw e;
			} // end try/catch
		}
	}

	
	@Override
	public int[] getLabels() {
		try {
			return VecUtils.copy(labels);
		} catch(NullPointerException npe) {
			String error = "model has not yet been fit";
			error(error);
			throw new ModelNotFitException(error);
		}
	}

	@Override
	public Algo getLoggerTag() {
		return com.clust4j.log.Log.Tag.Algo.HDBSCAN;
	}

	@Override
	public String getName() {
		return "HDBSCAN";
	}

	@Override
	public int getNumberOfIdentifiedClusters() {
		return numClusters;
	}

	@Override
	public int getNumberOfNoisePoints() {
		return numNoisey;
	}
	
	protected static int[] getLabels(HList<QuadTup<Integer, Integer, Double, Integer>> condensed,
									TreeMap<Integer, Double> stability) {
		
		double subTreeStability;
		double[][] tmpClusterTree;
		int parent;
		HList<Integer> nodes, clusters;
		
		// Get descending sorted key set
		int ct = 0;
		HList<Integer> nodeList = new HList<>();
		for(Integer d: stability.descendingKeySet())
			if(++ct < stability.size()) // exclude the root...
				nodeList.add(d);
		
		
		// Within this list, save which nodes map to parents that have the same value as the node...
		TreeMap<Integer, HList<QuadTup<Integer, Integer, Double, Integer>>> nodeMap = new TreeMap<>();
		
		// [parent, child, lambda, size]
		int maxChildSize = Integer.MIN_VALUE;
		HList<QuadTup<Integer, Integer, Double, Integer>> clusterTree = new HList<>();
		for(QuadTup<Integer, Integer, Double, Integer> branch: condensed) {
			parent = branch.one;
			if(!nodeMap.containsKey(parent))
				nodeMap.put(parent, new HList<QuadTup<Integer, Integer, Double, Integer>>());
			nodeMap.get(parent).add(branch);
			
			if(branch.four > 1) // where childSize > 1
				clusterTree.add(branch);
			else if(branch.four == 1) {
				if(branch.two > maxChildSize)
					maxChildSize = branch.two;
			}
		}
		
		// Build the tmp cluster tree
		tmpClusterTree = new double[clusterTree.size()][4];
		for(int i = 0; i < tmpClusterTree.length; i++) {
			tmpClusterTree[i] = new double[]{
				clusterTree.get(i).one,
				clusterTree.get(i).two,
				clusterTree.get(i).three,
				clusterTree.get(i).four,
			};
		}
		
		// Get cluster TreeMap
		TreeMap<Integer, Boolean> isCluster = new TreeMap<>();
		for(Integer d: nodeList) // init as true
			isCluster.put(d, true);
		
		// Big loop
		HList<QuadTup<Integer, Integer, Double, Integer>> childSelection;
		//int numPoints = maxChildSize + 1;
		for(Integer node: nodeList) {
			childSelection = nodeMap.get(node);
			subTreeStability = 0;
			if(null != childSelection)
				for(QuadTup<Integer,Integer,Double,Integer> selection: childSelection) {
					subTreeStability += stability.get(selection.two);
				}
			
			if(subTreeStability > stability.get(new Double(node))) {
				isCluster.put(node, false);
				stability.put(node, subTreeStability);
			} else {
				nodes = LinkageTreeUtils.breadthFirstSearch(tmpClusterTree, node);
				for(Integer subNode: nodes)
					if(subNode != node)
						isCluster.put(subNode, false);
			}
		}
		
		// Set clusters
		clusters = new HList<>();
		for(Map.Entry<Integer, Boolean> entry: isCluster.entrySet())
			if(entry.getValue())
				clusters.add(entry.getKey());
		
		// Enumerate clusters
		TreeMap<Integer, Integer> reverseClusterMap = new TreeMap<>();
		TreeMap<Integer, Integer> clusterMap = new TreeMap<>();
		for(int n = 0; n < clusters.size(); n++) {
			clusterMap.put(n, clusters.get(n));
			reverseClusterMap.put(clusters.get(n), n);
		}
		
		return doLabeling(condensed, clusters, clusterMap);
	}
	
	protected static double[][] label(final double[][] tree) {
		double[][] result;
		int a, aa, b, bb, index;
		final int m = tree.length, n = tree[0].length, N = m + 1;
		double delta;
		
		result = new double[m][n+1];
		UnifyFind U = new UnifyFind(N);
		
		
		for(index = 0; index < m; index++) {
			
			a = (int)tree[index][0];
			b = (int)tree[index][1];
			delta = tree[index][2];
			
			aa = U.fastFind(a);
			bb = U.fastFind(b);
			
			result[index][0] = aa;
			result[index][1] = bb;
			result[index][2] = delta;
			result[index][3] = U.size[aa] + U.size[bb];
			
			U.union(aa, bb);
		}
		
		return result;
	}
	
	protected static double[][] singleLinkage(final double[][] dists) {
		final double[][] hierarchy = LinkageTreeUtils.mstLinkageCore(dists, dists.length);
		return label(MatUtils.sortAscByCol(hierarchy, 2));
	}
	
	protected static int[] treeToLabels(final double[][] X, 
			final double[][] single_linkage_tree, final int min_size) {
		
		final HList<QuadTup<Integer, Integer, Double, Integer>> condensed = 
				LinkageTreeUtils.condenseTree(single_linkage_tree, min_size);
		final TreeMap<Integer, Double> stability = LinkageTreeUtils.computeStability(condensed);
		
		return getLabels(condensed, stability);
	}
}
