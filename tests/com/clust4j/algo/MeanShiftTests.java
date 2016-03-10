package com.clust4j.algo;

import static org.junit.Assert.*;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.junit.Test;

import com.clust4j.GlobalState;
import com.clust4j.TestSuite;
import com.clust4j.algo.MeanShift.MeanShiftPlanner;
import com.clust4j.algo.MeanShift.MeanShiftSeed;
import com.clust4j.algo.NearestNeighbors.NearestNeighborsPlanner;
import com.clust4j.algo.RadiusNeighbors.RadiusNeighborsPlanner;
import com.clust4j.algo.preprocess.FeatureNormalization;
import com.clust4j.data.DataSet;
import com.clust4j.data.ExampleDataSets;
import com.clust4j.except.ModelNotFitException;
import com.clust4j.metrics.pairwise.Distance;
import com.clust4j.utils.EntryPair;
import com.clust4j.utils.MatUtils;
import com.clust4j.utils.VecUtils;

public class MeanShiftTests implements ClusterTest, ClassifierTest, ConvergeableTest, BaseModelTest {
	final static Array2DRowRealMatrix data_ = ExampleDataSets.IRIS.getData();

	@Test
	public void MeanShiftTest1() {
		final double[][] train_array = new double[][] {
			new double[] {0.0,  1.0,  2.0,  3.0},
			new double[] {5.0,  4.3,  19.0, 4.0},
			new double[] {9.06, 12.6, 3.5,  9.0}
		};
		
		final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(train_array);
		
		MeanShift ms = new MeanShift(mat, new MeanShift
			.MeanShiftPlanner(0.5)
				.setVerbose(true)).fit();
		System.out.println();
		
		assertTrue(ms.getNumberOfIdentifiedClusters() == 3);
		assertTrue(ms.getNumberOfNoisePoints() == 0);
		assertTrue(ms.hasWarnings()); // will be because we don't standardize
	}
	
	@Test
	public void MeanShiftTest2() {
		final double[][] train_array = new double[][] {
			new double[] {0.001,  1.002,   0.481,   3.029,  2.019},
			new double[] {0.426,  1.291,   0.615,   2.997,  3.018},
			new double[] {6.019,  5.069,   3.681,   5.998,  5.182},
			new double[] {5.928,  4.972,   4.013,   6.123,  5.004},
			new double[] {12.091, 153.001, 859.013, 74.852, 3.091}
		};
		
		final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(train_array);
		
		MeanShift ms = new MeanShift(mat, new MeanShift
			.MeanShiftPlanner(0.5)
				.setMaxIter(100)
				.setMinChange(0.0005)
				.setSeed(new Random(100))
				.setVerbose(true)).fit();
		assertTrue(ms.getNumberOfIdentifiedClusters() == 4);
		assertTrue(ms.getLabels()[2] == ms.getLabels()[3]);
		System.out.println();
		
		
		ms = new MeanShift(mat, new MeanShift
			.MeanShiftPlanner(0.05)
				.setVerbose(true)).fit();
		assertTrue(ms.getNumberOfIdentifiedClusters() == 5);
		assertTrue(ms.hasWarnings()); // will because not normalizing
		System.out.println();
	}
	
	@Test
	public void MeanShiftTest3() {
		final double[][] train_array = new double[][] {
			new double[] {0.001,  1.002,   0.481,   3.029,  2.019},
			new double[] {0.426,  1.291,   0.615,   2.997,  3.018},
			new double[] {6.019,  5.069,   3.681,   5.998,  5.182},
			new double[] {5.928,  4.972,   4.013,   6.123,  5.004},
			new double[] {12.091, 153.001, 859.013, 74.852, 3.091}
		};
		
		final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(train_array);
		
		MeanShift ms = new MeanShift(mat, 0.5).fit();
		assertTrue(ms.getNumberOfIdentifiedClusters() == 4);
		assertTrue(ms.getLabels()[2] == ms.getLabels()[3]);
		
		MeanShiftPlanner msp = new MeanShiftPlanner(0.5);
		MeanShift ms1 = msp.buildNewModelInstance(mat).fit();
		
		assertTrue(ms1.getBandwidth() == 0.5);
		assertTrue(ms1.didConverge());
		assertTrue(MatUtils.equalsExactly(ms1.getKernelSeeds(), train_array));
		assertTrue(ms1.getMaxIter() == MeanShift.DEF_MAX_ITER);
		assertTrue(ms1.getConvergenceTolerance() == MeanShift.DEF_TOL);
		assertTrue(ms.getNumberOfIdentifiedClusters() == ms1.getNumberOfIdentifiedClusters());
		assertTrue(VecUtils.equalsExactly(ms.getLabels(), ms1.getLabels()));
		
		// check that we can get the centroids...
		assertTrue(null != ms.getCentroids());
	}
	
	@Test(expected=ModelNotFitException.class)
	public void testMeanShiftMFE1() {
		final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(MatUtils.randomGaussian(50, 2));
		MeanShift ms = new MeanShift(mat, new MeanShiftPlanner());
		ms.getLabels();
	}
	
	@Test(expected=ModelNotFitException.class)
	public void testMeanShiftMFE2() {
		final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(MatUtils.randomGaussian(50, 2));
		MeanShift ms = new MeanShift(mat, 0.5);
		ms.getCentroids();
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testMeanShiftIAEConst() {
		final Array2DRowRealMatrix mat = new Array2DRowRealMatrix(MatUtils.randomGaussian(50, 2));
		new MeanShift(mat, 0.0);
	}

	
	@Test
	public void testChunkSizeMeanShift() {
		final int chunkSize = 500;
		assertTrue(MeanShift.getNumChunks(chunkSize, 500) == 1);
		assertTrue(MeanShift.getNumChunks(chunkSize, 501) == 2);
		assertTrue(MeanShift.getNumChunks(chunkSize, 23) == 1);
		assertTrue(MeanShift.getNumChunks(chunkSize, 10) == 1);
	}
	
	@Test
	public void testMeanShiftAutoBwEstimate1() {
		final double[][] x = TestSuite.bigMatrix;
		double bw = MeanShift.autoEstimateBW(new Array2DRowRealMatrix(x, false), 0.3, Distance.EUCLIDEAN, new Random());
		new MeanShift(new Array2DRowRealMatrix(x), bw).fit();
	}
	
	@Test
	public void testMeanShiftAutoBwEstimate2() {
		final double[][] x = TestSuite.bigMatrix;
		MeanShift ms = new MeanShift(new Array2DRowRealMatrix(x), 
			new MeanShiftPlanner().setVerbose(true)).fit();
		System.out.println();
		assertTrue(ms.itersElapsed() >= 1);
		
		ms.fit(); // re-fit
		System.out.println();
	}
	
	@Test
	public void testMeanShiftAutoBwEstimate3() {
		final double[][] x = TestSuite.bigMatrix;
		MeanShift ms = new MeanShift(new Array2DRowRealMatrix(x)).fit();
		assertTrue(ms.getBandwidth() == 0.9148381982960355);
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testMeanShiftAutoBwEstimateException1() {
		final double[][] x = TestSuite.bigMatrix;
		new MeanShift(new Array2DRowRealMatrix(x), 
			new MeanShiftPlanner()
				.setAutoBandwidthEstimationQuantile(1.1));
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testMeanShiftAutoBwEstimateException2() {
		final double[][] x = TestSuite.bigMatrix;
		new MeanShift(new Array2DRowRealMatrix(x), 
			new MeanShiftPlanner()
				.setAutoBandwidthEstimationQuantile(0.0));
	}

	@Test
	@Override
	public void testDefConst() {
		new MeanShift(ExampleDataSets.IRIS.getData());
	}

	@Test
	@Override
	public void testArgConst() {
		new MeanShift(data_, 0.05);
	}

	@Test
	@Override
	public void testPlannerConst() {
		new MeanShift(data_, new MeanShiftPlanner(0.05));
	}

	@Test
	@Override
	public void testFit() {
		new MeanShift(data_,
			new MeanShiftPlanner()).fit();
	}

	@Test
	@Override
	public void testFromPlanner() {
		new MeanShiftPlanner()
			.buildNewModelInstance(data_);
	}

	@Test
	@Override
	public void testItersElapsed() {
		assertTrue(new MeanShift(data_, 
				new MeanShiftPlanner()).fit().itersElapsed() > 0);
	}

	@Test
	@Override
	public void testConverged() {
		assertTrue(new MeanShift(data_, 
				new MeanShiftPlanner()).fit().didConverge());
	}

	@Test
	@Override
	public void testScoring() {
		new MeanShift(data_,
			new MeanShiftPlanner()).fit().silhouetteScore();
	}

	@Test
	@Override
	public void testSerialization() throws IOException, ClassNotFoundException {
		MeanShift ms = new MeanShift(data_,
			new MeanShiftPlanner(0.5)
				.setVerbose(true)).fit();
		System.out.println();
		
		final double n = ms.getNumberOfNoisePoints();
		ms.saveModel(new FileOutputStream(TestSuite.tmpSerPath));
		assertTrue(TestSuite.file.exists());
		
		MeanShift ms2 = (MeanShift)MeanShift.loadModel(new FileInputStream(TestSuite.tmpSerPath));
		assertTrue(ms2.getNumberOfNoisePoints() == n);
		assertTrue(ms.equals(ms2));
		Files.delete(TestSuite.path);
	}
	
	@Test
	public void testAutoEstimation() {
		Array2DRowRealMatrix iris = ExampleDataSets.IRIS.getData();
		final double[][] X = iris.getData();
		
		// MS estimates bw at 1.202076812799869
		final double bandwidth = 1.202076812799869;
		assertTrue(MeanShift.autoEstimateBW(iris, 0.3, 
			Distance.EUCLIDEAN, GlobalState.DEFAULT_RANDOM_STATE) == bandwidth);
		
		// Asserting fit works without breaking things...
		RadiusNeighbors r = new RadiusNeighbors(iris,
			new RadiusNeighborsPlanner(bandwidth)).fit();
		
		TreeSet<MeanShiftSeed> centers = new TreeSet<>();
		for(double[] seed: X)
			centers.add(MeanShift.singleSeed(seed, r, X, 300));
		
		assertTrue(centers.size() == 7);

		double[][] expected_dists = new double[][]{
			new double[]{6.2114285714285691, 2.8928571428571428, 4.8528571428571423, 1.6728571428571426},
			new double[]{6.1927536231884037, 2.8768115942028984, 4.8188405797101437, 1.6463768115942023},
			new double[]{6.1521739130434767, 2.850724637681159,  4.7405797101449272, 1.6072463768115937},
			new double[]{6.1852941176470564, 2.8705882352941177, 4.8058823529411754, 1.6397058823529407},
			new double[]{6.1727272727272711, 2.874242424242424,  4.7757575757575745, 1.6287878787878785},
			new double[]{5.0163265306122451, 3.440816326530614,  1.46734693877551,   0.24285714285714283},
			new double[]{5.0020833333333341, 3.4208333333333356, 1.4666666666666668, 0.23958333333333334}
		};
		
		int[] expected_centers= new int[]{
			70, 69, 69, 68, 66, 49, 48
		};
		
		int idx = 0;
		for(MeanShiftSeed seed: centers) {
			assertTrue(VecUtils.equalsWithTolerance(seed.dists, expected_dists[idx], 1e-1));
			assertTrue(seed.count == expected_centers[idx]);
			idx++;
		}
	}
	

	
	// Hard condition to force..
	@Test//(expected=com.clust4j.except.IllegalClusterStateException.class)
	public void MeanShiftTest4() {
		DataSet iris = ExampleDataSets.IRIS;
		final Array2DRowRealMatrix data = iris.getData();
		
		new MeanShift(data, 
			new MeanShift.MeanShiftPlanner()
				.setScale(true)
				.setVerbose(true)).fit();
		System.out.println();
	}
	
	@Test
	public void testAutoEstimationWithScale() {
		Array2DRowRealMatrix iris = (Array2DRowRealMatrix)FeatureNormalization
			.STANDARD_SCALE.operate(ExampleDataSets.IRIS.getData());
		final double[][] X = iris.getData();
		
		// MS estimates bw at 1.5971266273437668
		final double bandwidth = 1.5971266273437668;
		assertTrue(MeanShift.autoEstimateBW(iris, 0.3, 
			Distance.EUCLIDEAN, GlobalState.DEFAULT_RANDOM_STATE) == bandwidth);
		
		// Asserting fit works without breaking things...
		RadiusNeighbors r = new RadiusNeighbors(iris,
			new RadiusNeighborsPlanner(bandwidth)).fit();
				
		TreeSet<MeanShiftSeed> centers = new TreeSet<>();
		for(double[] seed: X)
			centers.add(MeanShift.singleSeed(seed, r, X, 300));
		
		assertTrue(centers.size() == 5);
		
		double[][] expected_dists = new double[][]{
			new double[]{ 0.50161528154395962, -0.31685274298813487,  0.65388162422893481, 0.65270450741975761},
			new double[]{ 0.4829041180399124,  -0.3184802762043775,   0.6434194172372906,  0.6471200248238047 },
			new double[]{ 0.52001211065400177, -0.29561728795619946,  0.67106269515983397, 0.67390853215763813},
			new double[]{ 0.54861244890482475, -0.25718786696105495,  0.68964559485632182, 0.69326664641211422},
			new double[]{-1.0595457115461515,   0.74408909010240054, -1.2995708885010491, -1.2545442961404225 }
		};
		
		int[] expected_centers= new int[]{
			82, 81, 80, 77, 45
		};
		
		int idx = 0;
		for(MeanShiftSeed seed: centers) {
			assertTrue(VecUtils.equalsWithTolerance(seed.dists, expected_dists[idx], 1e-1));
			assertTrue(seed.count == expected_centers[idx]);
			idx++;
		}
		
		ArrayList<EntryPair<double[], Integer>> center_intensity = new ArrayList<>();
		for(MeanShiftSeed seed: centers) {
			if(null != seed) {
				center_intensity.add(seed.getPair());
			}
		}
		
		
		
		// Now test the actual method...
		EntryPair<ArrayList<EntryPair<double[],Integer>>, Integer> entry =
			MeanShift.getCenterIntensity(iris, bandwidth, X, GlobalState.DEFAULT_RANDOM_STATE, 
					Distance.EUCLIDEAN, 300);
		ArrayList<EntryPair<double[], Integer>> center_intensity2 = entry.getKey();
		assertTrue(center_intensity2.size() == center_intensity.size());
		
		
		final ArrayList<EntryPair<double[], Integer>> sorted_by_intensity = center_intensity;
		
		// test getting the unique vals
		idx = 0;
		final int m_prime = sorted_by_intensity.size();
		final Array2DRowRealMatrix sorted_centers = new Array2DRowRealMatrix(m_prime, iris.getColumnDimension());
		for(Map.Entry<double[], Integer> e: sorted_by_intensity)
			sorted_centers.setRow(idx++, e.getKey());
		
		
		// Create a boolean mask, init true
		final boolean[] unique = new boolean[m_prime];
		for(int i = 0; i < unique.length; i++) unique[i] = true;
		
		// Fit the new neighbors model
		RadiusNeighbors nbrs = new RadiusNeighbors(sorted_centers,
			new RadiusNeighborsPlanner(bandwidth)
				.setVerbose(false)).fit();
		
		
		// Iterate over sorted centers and query radii
		int[] indcs;
		double[] center;
		for(int i = 0; i < m_prime; i++) {
			if(unique[i]) {
				center = sorted_centers.getRow(i);
				indcs = nbrs.getNeighbors(
					new double[][]{center}, bandwidth)
						.getIndices()[0];
				
				for(int id: indcs) {
					unique[id] = false;
				}
				
				unique[i] = true; // Keep this as true
			}
		}
		
		
		// Now assign the centroids...
		int redundant_ct = 0;
		final ArrayList<double[]> centroids =  new ArrayList<>();
		for(int i = 0; i < unique.length; i++) {
			if(unique[i]) {
				centroids.add(sorted_centers.getRow(i));
			}
		}
		
		redundant_ct = unique.length - centroids.size();
		
		assertTrue(redundant_ct == 3);
		assertTrue(centroids.size() == 2);
		assertTrue(VecUtils.equalsExactly(centroids.get(0), new double[]{
			 0.4999404345258573, -0.3217963110452486, 0.651751961050506, 0.6504383581073979
		}));
		

		assertTrue(VecUtils.equalsExactly(centroids.get(1), new double[]{
			-1.0560079864392453, 0.7555834087538167, -1.2954688594835067, -1.2498288991228368
		}));
		
		
		// also put the centroids into a matrix. We have to
		// wait to perform this op, because we have to know
		// the size of centroids first...
		Array2DRowRealMatrix clust_centers = new Array2DRowRealMatrix(centroids.size(), iris.getColumnDimension());
		for(int i = 0; i < clust_centers.getRowDimension(); i++)
			clust_centers.setRow(i, centroids.get(i));
		
		// The final nearest neighbors model -- if this works, we are in the clear...
		NearestNeighbors nn = new NearestNeighbors(clust_centers,
			new NearestNeighborsPlanner(1)).fit();
	}
}
