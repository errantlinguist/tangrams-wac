/*
 * 	Copyright 2017 Todd Shore
 *
 *	Licensed under the Apache License, Version 2.0 (the "License");
 *	you may not use this file except in compliance with the License.
 *	You may obtain a copy of the License at
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software
 *	distributed under the License is distributed on an "AS IS" BASIS,
 *	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *	See the License for the specific language governing permissions and
 *	limitations under the License.
 */
package se.kth.speech.coin.tangrams.wac.logistic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kth.speech.coin.tangrams.wac.data.Session;
import se.kth.speech.coin.tangrams.wac.data.SessionSet;
import se.kth.speech.coin.tangrams.wac.data.SessionSetReader;
import se.kth.speech.coin.tangrams.wac.logistic.LogisticModel.TrainingData;
import se.kth.speech.coin.tangrams.wac.logistic.RankScorer.ClassificationResult;

public final class CrossValidator<R> { // NO_UCD (use default)

	public static final class Exception extends RuntimeException { // NO_UCD
																	// (use
																	// private)

		/**
		 *
		 */
		private static final long serialVersionUID = -1636897113752283942L;

		private static final String createMessage(final SessionSet training, final SessionSet testing,
				final java.lang.Exception cause) {
			final List<String> trainingSessionNames = Arrays.asList(training.getSessions().stream().map(Session::getName).sorted().toArray(String[]::new));
			final List<String> testSessionNames = Arrays.asList(testing.getSessions().stream().map(Session::getName).sorted().toArray(String[]::new));
			return String.format(
					"A(n) %s occurred while cross-validating with a training set of %d session(s) and testing on sessions %s. Training sets: %s",
					cause, training.size(), testSessionNames, trainingSessionNames);
		}

		private Exception(final SessionSet training, final SessionSet testing, final java.lang.Exception cause) {
			super(createMessage(training, testing, cause), cause);
		}

	}

	public static final class Result<R> {

		private final int crossValidationIteration;

		private final R evalResult;

		private final Map<ModelParameter, Object> modelParams;


		Result(final int crossValidationIteration, final R evalResult, final Map<ModelParameter, Object> modelParams) {
			this.crossValidationIteration = crossValidationIteration;
			this.evalResult = evalResult;
			this.modelParams = modelParams;
		}


		/**
		 * @return the crossValidationIteration
		 */
		int getCrossValidationIteration() {
			return crossValidationIteration;
		}

		/**
		 * @return the evalResult
		 */
		R getEvalResult() {
			return evalResult;
		}

		/**
		 * @return the modelParams
		 */
		Map<ModelParameter, Object> getModelParams() {
			return modelParams;
		}

	}

	private enum Parameter implements Supplier<Option> {
		HELP("?") {
			@Override
			public Option get() {
				return Option.builder(optName).longOpt("help").desc("Prints this message.").build();
			}
		},
		REFERRING_TOKENS("t") {
			@Override
			public Option get() {
				return Option.builder(optName).longOpt("referring-tokens")
						.desc("The file to read utterance referring-language mappings from.").hasArg().argName("path")
						.type(File.class).required().build();
			}
		};

		protected final String optName;

		private Parameter(final String optName) {
			this.optName = optName;
		}

	}

	private static final Logger LOGGER = LoggerFactory.getLogger(CrossValidator.class);

	private static final Options OPTIONS = createOptions();

	public static void main(final CommandLine cl) throws ParseException, IOException { // NO_UCD
																						// (use
																						// private)
		if (cl.hasOption(Parameter.HELP.optName)) {
			printHelp();
		} else {
			final Path[] inpaths = cl.getArgList().stream().map(Paths::get).toArray(Path[]::new);
			if (inpaths.length < 1) {
				throw new ParseException("No input paths specified.");
			} else {
				LOGGER.info("Will read sessions from {}.", Arrays.toString(inpaths));
				final Map<ModelParameter, Object> modelParams = ModelParameter.createParamValueMap(cl);
				final Path refTokenFilePath = ((File) cl.getParsedOptionValue(Parameter.REFERRING_TOKENS.optName))
						.toPath();
				final SessionSet set = new SessionSetReader(refTokenFilePath).apply(inpaths);
				LOGGER.info("Will run cross-validation using {} session(s).", set.size());
				final ForkJoinPool executor = ForkJoinPool.commonPool();
				// NOTE: No need to explicitly shut down common pool
				LOGGER.info("Will run cross-validation using a(n) {} instance with a parallelism level of {}.",
						executor.getClass().getSimpleName(), executor.getParallelism());
				final CrossValidationTablularDataWriter resultWriter = new CrossValidationTablularDataWriter(
						System.out);
				final Supplier<LogisticModel> modelFactory = () -> new LogisticModel(modelParams, executor);
				final Function<LogisticModel, Function<SessionSet, Stream<RoundEvaluationResult<ClassificationResult>>>> evaluatorFactory = model -> model
						.createRankScorer();
				final CrossValidator<RoundEvaluationResult<ClassificationResult>> crossValidator = new CrossValidator<>(
						modelParams, modelFactory, evaluatorFactory, TestSessions::isTestSession, executor);
				crossValidator.crossValidate(set, evalResult -> {
					try {
						resultWriter.accept(evalResult);
					} catch (final IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			}
		}
	}

	public static void main(final String[] args) throws IOException {
		final CommandLineParser parser = new DefaultParser();
		try {
			final CommandLine cl = parser.parse(OPTIONS, args);
			main(cl);
		} catch (final ParseException e) {
			System.out.println(String.format("An error occurred while parsing the command-line arguments: %s", e));
			printHelp();
		}
	}

	private static Options createOptions() {
		final Options result = new Options();
		Arrays.stream(Parameter.values()).map(Parameter::get).forEach(result::addOption);
		Arrays.stream(ModelParameter.values()).map(ModelParameter::createCLIOptionBuilder).map(Option.Builder::build)
				.forEach(result::addOption);
		return result;
	}

	private static void printHelp() {
		final HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp(CrossValidator.class.getName() + " INPATHS...", OPTIONS);
	}

	private final int cvIterBatchSize;

	private final Function<LogisticModel, Function<SessionSet, Stream<R>>> evaluatorFactory;

	private final Executor executor;

	private final Supplier<LogisticModel> modelFactory;

	private final Map<ModelParameter, Object> modelParams;
	
	private final Predicate<? super Session> testSessionMatcher;

	public CrossValidator(final Map<ModelParameter, Object> modelParams, final Supplier<LogisticModel> modelFactory, // NO_UCD
																														// (use
																														// default)
			final Executor executor, final Function<LogisticModel, Function<SessionSet, Stream<R>>> evaluatorFactory, final Predicate<? super Session> testSessionMatcher,
			final int cvIterBatchSize) {
		this.modelParams = modelParams;
		this.modelFactory = modelFactory;
		this.executor = executor;
		this.evaluatorFactory = evaluatorFactory;
		this.testSessionMatcher = testSessionMatcher;
		this.cvIterBatchSize = cvIterBatchSize;
	}

	public CrossValidator(final Map<ModelParameter, Object> modelParams, final Supplier<LogisticModel> modelFactory,
			final Function<LogisticModel, Function<SessionSet, Stream<R>>> evaluatorFactory,  final Predicate<? super Session> testSessionMatcher, final Executor executor) {
		this(modelParams, modelFactory, executor, evaluatorFactory, testSessionMatcher, 1);
	}

	/**
	 * Performs cross validation on a {@link SessionSet}.
	 *
	 * @param set
	 *            The {@link SessionSet} to perform cross-validation on.
	 * @param resultHandler
	 *            A {@link Consumer} of individual {@link Result} objects
	 *            representing the results of testing on a single round in a
	 *            single session.
	 */
	public void crossValidate(final SessionSet set, // NO_UCD (use default)
			final Consumer<? super Result<R>> resultHandler) {
		final CompletableFuture<Void> allFinished = CompletableFuture
				.allOf(crossValidateAsynchronously(set, resultHandler).toArray(CompletableFuture[]::new));
		allFinished.join();
	}

	/**
	 * Performs cross validation on a {@link SessionSet} asynchronously.
	 *
	 * @param set
	 *            The {@link SessionSet} to perform cross-validation on.
	 * @param resultHandler
	 *            A {@link Consumer} of individual {@link Result} objects
	 *            representing the results of testing on a single round in a
	 *            single session.
	 * @return A {@link Stream} of <em>n</em> {@link CompletableFuture}, each of
	 *         which representing the completion of one of <em>n</em>
	 *         cross-validation test iterations, as defined by
	 *         {@link ModelParameter#CROSS_VALIDATION_ITER_COUNT}.
	 */
	public Stream<CompletableFuture<Void>> crossValidateAsynchronously(final SessionSet set, // NO_UCD
																								// (use
																								// private)
			final Consumer<? super Result<R>> resultHandler) {
		final int cvIterCount = (Integer) modelParams.get(ModelParameter.CROSS_VALIDATION_ITER_COUNT);
		// Pass the same Random instance to each cross-validation iteration so
		// that each iteration is potentially different
		final long randomSeed = (Long) modelParams.get(ModelParameter.RANDOM_SEED);
		final Random random = new Random(randomSeed);
		final Stream.Builder<CompletableFuture<Void>> cvIterationJobs = Stream.builder();

		final int[][] cvIterBatches = createBatchCrossValidationIterIdArrays(cvIterCount);
		for (final int[] cvIters : cvIterBatches) {
			cvIterationJobs.add(CompletableFuture.runAsync(() -> {
				for (final int cvIter : cvIters) {
					set.crossValidate((training, testing) -> {
						try {
							final LogisticModel model = modelFactory.get();
							// NOTE: This is the original training data, NOT updated data!
							final TrainingData origTrainingData = model.train(training);
							assert origTrainingData != null;
							final Function<SessionSet, Stream<R>> evaluator = evaluatorFactory.apply(model);
							final Stream<R> roundEvalResults = evaluator.apply(testing);
							roundEvalResults.map(evalResult -> new Result<>(cvIter, evalResult, modelParams)).forEach(resultHandler);
						} catch (final ClassificationException e) {
							throw new Exception(training, testing, e);
						}
					}, modelParams, random, testSessionMatcher);
				}
			}, executor));
		}
		return cvIterationJobs.build();
	}

	private int calculateBatchCount(final int cvIterCount) {
		final double nonIntegralValue = cvIterCount / cvIterBatchSize;
		return nonIntegralValue >= 1.0 ? Math.toIntExact(Math.round(Math.ceil(nonIntegralValue))) : 1;
	}

	private int[][] createBatchCrossValidationIterIdArrays(final int cvIterCount) {
		final int cvBatchCount = calculateBatchCount(cvIterCount);
		final int[][] result = new int[cvBatchCount][];
		// Cross-validation iteration IDs are 1-indexed
		int nextCvIterId = 1;
		int remainingIters = cvIterCount;
		for (int batchId = 0; batchId < result.length; ++batchId) {
			final int batchSize = Math.min(cvIterBatchSize, remainingIters);
			final int[] batchCvIterIdArray = new int[batchSize];
			for (int batchCvIterIdArrayIdx = 0; batchCvIterIdArrayIdx < batchCvIterIdArray.length; ++batchCvIterIdArrayIdx) {
				batchCvIterIdArray[batchCvIterIdArrayIdx] = nextCvIterId++;
			}
			result[batchId] = batchCvIterIdArray;
			remainingIters -= batchSize;
		}

		assert remainingIters == 0;
		assert Arrays.stream(result).mapToInt(array -> array.length).sum() == cvIterCount;
		assert Arrays.stream(result).flatMapToInt(Arrays::stream).distinct().count() == cvIterCount;
		return result;
	}

}
