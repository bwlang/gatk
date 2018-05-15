package org.broadinstitute.hellbender.tools.copynumber;

import htsjdk.samtools.SAMSequenceDictionary;
import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.CommandLineProgram;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.argumentcollections.IntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.argumentcollections.OptionalIntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.copynumber.arguments.*;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.AnnotatedIntervalCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.SimpleCountCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.SimpleIntervalCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.LocatableMetadata;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.SimpleLocatableMetadata;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calls copy-number variants in germline samples given their counts and the corresponding output of
 * {@link DetermineGermlineContigPloidy}. The former should be either HDF5 or TSV count files generated by
 * {@link CollectReadCounts}.
 *
 * <h3>Introduction</h3>
 *
 * <p>Reliable detection of copy-number variation (CNV) from read-depth ("coverage" or "counts") data such as whole
 * exome sequencing (WES), whole genome sequencing (WGS), and custom gene sequencing panels requires a comprehensive
 * model to account for technical variation in library preparation and sequencing. The Bayesian model and the associated
 * inference scheme implemented in {@link GermlineCNVCaller} includes provisions for inferring and explaining away much
 * of the technical variation. Furthermore, CNV calling confidence is automatically adjusted for each sample and
 * genomic interval.
 *
 * <p>The parameters of the probabilistic model for read-depth bias and variance estimation (hereafter, "the coverage
 * model") can be automatically inferred by {@link GermlineCNVCaller} by providing a cohort of germline samples
 * sequenced using the same sequencing platform and library preparation protocol (in case of WES, the same capture
 * kit). We refer to this run mode of the tool as the <b>COHORT</b> mode. The number of samples required for the
 * COHORT mode depends on several factors such as the sequencing depth, tissue type/quality and similarity in the cohort,
 * and the stringency of following the library preparation and sequencing protocols. For WES and WGS samples, we
 * recommend including at least 30 samples.</p>
 *
 * <p>The parametrized coverage model can be used for CNV calling on future case samples provided that they are
 * strictly compatible with the cohort used to generate the model parameters (in terms of tissue type(s), library
 * preparation and sequencing protocols). We refer to this mode as the <b>CASE</b> run mode. There is no lower
 * limit on the number of samples for running {@link GermlineCNVCaller} in the case mode.</p>
 *
 * <p>In both tool run modes, {@link GermlineCNVCaller} requires karyotyping and global read-depth information for
 * all samples. Such information can be automatically generated by running {@link DetermineGermlineContigPloidy}
 * on all samples, and passed on to {@link GermlineCNVCaller} by providing the ploidy output calls using the argument
 * {@code contig-ploidy-calls}. The ploidy state of a contig is used as the <em>baseline</em>
 * ("default") copy-number state of all intervals contained in that contig for the corresponding sample. All other
 * copy-number states are treated as alternative states and get equal shares from the total alternative state
 * probability (set using the {@code p-alt} argument).</p>
 *
 * <h3>Python environment setup</h3>
 *
 * <p>The computation done by this tool, aside from input data parsing and validation, is performed outside of the Java
 * Virtual Machine and using the <em>gCNV computational python module</em>, namely {@code gcnvkernel}. It is crucial that
 * the user has properly set up a python conda environment with {@code gcnvkernel} and its dependencies
 * installed. If the user intends to run {@link GermlineCNVCaller} using one of the official GATK Docker images,
 * the python environment is already set up. Otherwise, the environment must be created and activated as described in the
 * main GATK README.md file.</p>
 *
 * <h3>Tool run modes</h3>
 * <dl>
 *     <dt>COHORT mode:</dt>
 *     <dd><p>The tool will be run in the COHORT mode using the argument {@code run-mode COHORT}.
 *      In this mode, coverage model parameters are inferred simultaneously with the CNV states. Depending on
 *      available memory, it may be necessary to run the tool over a subset of all intervals, which can be specified
 *      by -L. The specified intervals must be present in all of the input count files. The output will contain two
 *      subdirectories, one ending with "-model" and the other with "-calls".</p>
 *
 *      <p>The model subdirectory contains a snapshot of the inferred parameters of the coverage model, which may be
 *      used later for CNV calling in one or more similarly-sequenced samples as mentioned earlier. Optionally, the path
 *      to a previously obtained coverage model parametrization can be provided via the {@code model} argument
 *      in the COHORT mode, in which case, the provided parameters will be only used for model initialization and
 *      a new parametrization will be generated based on the input count files. Furthermore, the genomic intervals are
 *      set to those used in creating the previous parameterization and interval-related arguments will be ignored.
 *      Note that the newly obtained parametrization ultimately reflects the input count files from the last run,
 *      regardless of whether or not an initialization parameter set is given. If the users wishes to model coverage
 *      data from two or more cohorts simultaneously, all of the input counts files must be given to the tool at once.
 *
 *      <p>The calls subdirectory contains sequentially-ordered subdirectories for each sample, each listing various
 *      sample-specific estimated quantities such as the probability of various copy-number states for each interval,
 *      a parametrization of the GC curve, sample-specific unexplained variance, read-depth, and loadings of
 *      coverage bias factors.</p></dd>
 *
 *     <dt>CASE mode:</dt>
 *     <dd><p>The tool will be run in the CASE mode using the argument {@code run-mode CASE}. The path to a
 *      previously obtained coverage model parameter bundle must be provided via the {@code model} argument
 *      in this mode. The modeled intervals are specified by the parameter bundle, all interval-related arguments are
 *      ignored in this mode, and all model intervals must be present in all of the input count files. The tool output
 *      in the CASE mode is only the "-calls" subdirectory and is organized similarly to the COHORT mode.</p>
 *
 *      <p>Note that at the moment, this tool does not automatically verify the compatibility of the provided parametrization
 *      with the provided count files. Model compatibility may be assessed a posteriori by inspecting the magnitude of
 *      sample-specific unexplained variance of each sample, and asserting that they lie within the same range as
 *      those obtained from the cohort used to generate the parametrization. This manual step is expected to be made
 *      automatic in the future.</p>
 * </dl>
 *
 * <h3>Important Remarks</h3>
 * <dl>
 *     <dt>Choice of hyperparameters:</dt>
 *     <dd><p>The quality of coverage model parametrization and the sensitivity/precision of germline CNV calling are
 *     sensitive to the choice of model hyperparameters, including the prior probability of alternative copy-number states
 *     (set using the {@code p-alt} argument), prevalence of active (i.e. CNV-rich) intervals (set via the
 *     {@code p-active} argument), the coherence length of CNV events and active/silent domains
 *     across intervals (set using the {@code cnv-coherence-length} and {@code class-coherence-length} arguments,
 *     respectively), and the typical scale of interval- and sample-specific unexplained variance
 *     (set using the {@code interval-psi-scale} and {@code sample-psi-scale} arguments, respectively). It is crucial
 *     to note that these hyperparameters are <em>not</em> universal and must be tuned for each sequencing protocol
 *     and properly set at runtime.</p></dd>
 *
 *     <dt>Running {@link GermlineCNVCaller} on a subset of intervals:</dt>
 *     <dd><p>As mentioned earlier, it may be necessary to run the tool over a subset of all intervals depending on
 *     available memory. The number of intervals must be large enough to include a genomically diverse set of regions
 *     for reliable inference of the GC bias curve, as well as other bias factors. For WES and WGS, we recommend no less
 *     than 10000 consecutive intervals spanning at least 10 - 50 mb.</p></dd>
 *
 *     <dt>Memory requirements for the python subprocess ("gcnvkernel"):</dt>
 *     <dd><p>The computation done by this tool, for the most part, is performed outside of JVM and via a spawned
 *     python subprocess. The Java heap memory is only used for loading sample counts and preparing raw data for the
 *     python subprocess. The user must ensure that the machine has enough free physical memory for spawning and executing
 *     the python subprocess. Generally speaking, the resource requirements of this tool scale linearly with each of the
 *     number of samples, the number of modeled intervals, the highest copy number state, the number of bias factors, and
 *     the number of knobs on the GC curve. For example, the python subprocess requires approximately 16GB of physical memory
 *     for modeling 10000 intervals for 100 samples, with 16 maximum bias factors, maximum copy-number state of 10, and
 *     explicit GC bias modeling.</p></dd>
 * </dl>
 *
 * <h3>Usage examples</h3>
 *
 * <p>COHORT mode:</p>
 * <pre>
 * gatk GermlineCNVCaller \
 *   --run-mode COHORT \
 *   -L intervals.interval_list \
 *   --interval-merging-rule OVERLAPPING_ONLY \
 *   --contig-ploidy-calls path_to_contig_ploidy_calls \
 *   --input normal_1.counts.hdf5 \
 *   --input normal_2.counts.hdf5 \
 *   ... \
 *   --output output_dir \
 *   --output-prefix normal_cohort_run
 * </pre>
 *
 * <p>CASE mode:</p>
 * <pre>
 * gatk GermlineCNVCaller \
 *   --run-mode CASE \
 *   -L intervals.interval_list \
 *   --interval-merging-rule OVERLAPPING_ONLY \
 *   --contig-ploidy-calls path_to_contig_ploidy_calls \
 *   --model previous_model_path \
 *   --input normal_1.counts.hdf5 \
 *   ... \
 *   --output output_dir \
 *   --output-prefix normal_case_run
 * </pre>
 *
 * @author Mehrtash Babadi &lt;mehrtash@broadinstitute.org&gt;
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Calls copy-number variants in germline samples given their counts and " +
                "the output of DetermineGermlineContigPloidy",
        oneLineSummary = "Calls copy-number variants in germline samples given their counts and " +
                "the output of DetermineGermlineContigPloidy",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
@BetaFeature
public final class GermlineCNVCaller extends CommandLineProgram {
    public enum RunMode {
        COHORT, CASE
    }

    public static final String COHORT_DENOISING_CALLING_PYTHON_SCRIPT = "cohort_denoising_calling.py";
    public static final String CASE_SAMPLE_CALLING_PYTHON_SCRIPT = "case_denoising_calling.py";

    //name of the interval file output by the python code in the model directory
    public static final String INPUT_MODEL_INTERVAL_FILE = "interval_list.tsv";

    public static final String MODEL_PATH_SUFFIX = "-model";
    public static final String CALLS_PATH_SUFFIX = "-calls";
    public static final String TRACKING_PATH_SUFFIX = "-tracking";

    public static final String CONTIG_PLOIDY_CALLS_DIRECTORY_LONG_NAME = "contig-ploidy-calls";
    public static final String RUN_MODE_LONG_NAME = "run-mode";

    @Argument(
            doc = "Input read-count files containing integer read counts in genomic intervals for all samples.  " +
                    "All intervals specified via -L must be contained; " +
                    "if none are specified, then intervals must be identical and in the same order for all samples.",
            fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME,
            minElements = 1
    )
    private List<File> inputReadCountFiles = new ArrayList<>();

    @Argument(
            doc = "Tool run-mode.",
            fullName = RUN_MODE_LONG_NAME
    )
    private RunMode runMode;

    @Argument(
            doc = "Input contig-ploidy calls directory (output of DetermineGermlineContigPloidy).",
            fullName = CONTIG_PLOIDY_CALLS_DIRECTORY_LONG_NAME
    )
    private String inputContigPloidyCallsDir;

    @Argument(
            doc = "Input denoising-model directory. In the COHORT mode, this argument is optional and if provided," +
                    "a new model will be built using this input model to initialize. In the CASE mode, the denoising " +
                    "model parameters set to this input model and therefore, this argument is required.",
            fullName = CopyNumberStandardArgument.MODEL_LONG_NAME,
            optional = true
    )
    private String inputModelDir = null;

    @Argument(
            doc = "Input annotated-interval file containing annotations for GC content in genomic intervals " +
                    "(output of AnnotateIntervals).  All intervals specified via -L must be contained.  " +
                    "This input should not be provided if an input denoising-model directory is given (the latter " +
                    "already contains the annotated-interval file).",
            fullName = CopyNumberStandardArgument.ANNOTATED_INTERVALS_FILE_LONG_NAME,
            optional = true
    )
    private File inputAnnotatedIntervalsFile = null;

    @Argument(
            doc = "Prefix for output filenames.",
            fullName =  CopyNumberStandardArgument.OUTPUT_PREFIX_LONG_NAME
    )
    private String outputPrefix;

    @Argument(
            doc = "Output directory.",
            fullName =  StandardArgumentDefinitions.OUTPUT_LONG_NAME,
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME
    )
    private String outputDir;

    @ArgumentCollection
    protected IntervalArgumentCollection intervalArgumentCollection
            = new OptionalIntervalArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineDenoisingModelArgumentCollection germlineDenoisingModelArgumentCollection =
            new GermlineDenoisingModelArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineCallingArgumentCollection germlineCallingArgumentCollection
            = new GermlineCallingArgumentCollection();

    @Advanced
    @ArgumentCollection
    private GermlineCNVHybridADVIArgumentCollection germlineCNVHybridADVIArgumentCollection
            = new GermlineCNVHybridADVIArgumentCollection();

    private SimpleIntervalCollection specifiedIntervals;
    private File specifiedIntervalsFile;

    @Override
    protected void onStartup() {
        /* check for successful import of gcnvkernel */
        PythonScriptExecutor.checkPythonEnvironmentForPackage("gcnvkernel");
    }

    @Override
    protected Object doWork() {
        validateArguments();

        //read in count files, validate they contain specified subset of intervals, and output
        //count files for these intervals to temporary files
        final List<File> intervalSubsetReadCountFiles = writeIntervalSubsetReadCountFiles();

        //call python inference code
        final boolean pythonReturnCode = executeGermlineCNVCallerPythonScript(intervalSubsetReadCountFiles);

        if (!pythonReturnCode) {
            throw new UserException("Python return code was non-zero.");
        }

        logger.info("Germline denoising and CNV calling complete.");

        return "SUCCESS";
    }

    private void validateArguments() {
        inputReadCountFiles.forEach(IOUtils::canReadFile);
        Utils.validateArg(inputReadCountFiles.size() == new HashSet<>(inputReadCountFiles).size(),
                "List of input read-count files cannot contain duplicates.");

        if (inputModelDir != null) {
            Utils.validateArg(new File(inputModelDir).exists(),
                    String.format("Input denoising-model directory %s does not exist.", inputModelDir));
        }

        if (inputModelDir != null) {
            //intervals are retrieved from the input model directory
            specifiedIntervalsFile = new File(inputModelDir, INPUT_MODEL_INTERVAL_FILE);
            IOUtils.canReadFile(specifiedIntervalsFile);
            specifiedIntervals = new SimpleIntervalCollection(specifiedIntervalsFile);
        } else {
            //get sequence dictionary and intervals from the first read-count file to use to validate remaining files
            //(this first file is read again below, which is slightly inefficient but is probably not worth the extra code)
            final File firstReadCountFile = inputReadCountFiles.get(0);
            final SimpleCountCollection firstReadCounts = SimpleCountCollection.read(firstReadCountFile);
            final SAMSequenceDictionary sequenceDictionary = firstReadCounts.getMetadata().getSequenceDictionary();
            final LocatableMetadata metadata = new SimpleLocatableMetadata(sequenceDictionary);

            if (intervalArgumentCollection.intervalsSpecified()) {
                logger.info("Intervals specified...");
                CopyNumberArgumentValidationUtils.validateIntervalArgumentCollection(intervalArgumentCollection);
                specifiedIntervals = new SimpleIntervalCollection(metadata,
                        intervalArgumentCollection.getIntervals(sequenceDictionary));
            } else {
                logger.info(String.format("Retrieving intervals from first read-count file (%s)...",
                        firstReadCountFile));
                specifiedIntervals = new SimpleIntervalCollection(metadata, firstReadCounts.getIntervals());
            }

            //in cohort mode, intervals are specified via -L; we write them to a temporary file
            specifiedIntervalsFile = IOUtils.createTempFile("intervals", ".tsv");
            //get GC content (null if not provided)
            final AnnotatedIntervalCollection subsetAnnotatedIntervals =
                    CopyNumberArgumentValidationUtils.validateAnnotatedIntervalsSubset(
                            inputAnnotatedIntervalsFile, specifiedIntervals, logger);
            if (subsetAnnotatedIntervals != null) {
                subsetAnnotatedIntervals.write(specifiedIntervalsFile);
            } else {
                specifiedIntervals.write(specifiedIntervalsFile);
            }
        }

        if (runMode.equals(RunMode.COHORT)) {
            logger.info("Running the tool in the COHORT mode...");
            Utils.validateArg(inputReadCountFiles.size() > 1, "At least two samples must be provided in the " +
                    "COHORT mode");
            if (inputModelDir != null) {
                logger.info("(advanced feature) A denoising-model directory is provided in the COHORT mode; " +
                        "using the model for initialization and ignoring specified and/or annotated intervals.");
            }
        } else { // case run-mode
            logger.info("Running the tool in the CASE mode...");
            Utils.validateArg(inputModelDir != null, "An input denoising-model directory must be provided in the " +
                    "CASE mode.");
            if (intervalArgumentCollection.intervalsSpecified()) {
                throw new UserException.BadInput("Invalid combination of inputs: Running in CASE mode, " +
                        "but intervals were provided.");
            }
            if (inputAnnotatedIntervalsFile != null) {
                throw new UserException.BadInput("Invalid combination of inputs: Running in CASE mode," +
                        "but annotated intervals were provided.");
            }
        }

        Utils.nonNull(outputPrefix);
        Utils.validateArg(new File(inputContigPloidyCallsDir).exists(),
                String.format("Input contig-ploidy calls directory %s does not exist.", inputContigPloidyCallsDir));
        Utils.validateArg(new File(outputDir).exists(),
                String.format("Output directory %s does not exist.", outputDir));

        germlineCallingArgumentCollection.validate();
        germlineDenoisingModelArgumentCollection.validate();
        germlineCNVHybridADVIArgumentCollection.validate();
    }

    private List<File> writeIntervalSubsetReadCountFiles() {
        logger.info("Validating and aggregating data from input read-count files...");
        final int numSamples = inputReadCountFiles.size();
        final ListIterator<File> inputReadCountFilesIterator = inputReadCountFiles.listIterator();
        final List<File> intervalSubsetReadCountFiles = new ArrayList<>(numSamples);
        final Set<SimpleInterval> intervalSubset = new HashSet<>(specifiedIntervals.getRecords());

        while (inputReadCountFilesIterator.hasNext()) {
            final int sampleIndex = inputReadCountFilesIterator.nextIndex();
            final File inputReadCountFile = inputReadCountFilesIterator.next();
            logger.info(String.format("Aggregating read-count file %s (%d / %d)",
                    inputReadCountFile, sampleIndex + 1, numSamples));
            final SimpleCountCollection readCounts = SimpleCountCollection.read(inputReadCountFile);
            Utils.validateArg(CopyNumberArgumentValidationUtils.isSameDictionary(
                    readCounts.getMetadata().getSequenceDictionary(),
                    specifiedIntervals.getMetadata().getSequenceDictionary()),
                    String.format("Sequence dictionary for read-count file %s does not match those in " +
                            "other read-count files.", inputReadCountFile));
            Utils.validateArg(new HashSet<>(readCounts.getIntervals()).containsAll(intervalSubset),
                    String.format("Intervals for read-count file %s do not contain all specified intervals.",
                            inputReadCountFile));
            final File intervalSubsetReadCountFile = IOUtils.createTempFile("sample-" + sampleIndex, ".tsv");
            new SimpleCountCollection(
                    readCounts.getMetadata(),
                    readCounts.getRecords().stream()
                            .filter(c -> intervalSubset.contains(c.getInterval()))
                            .collect(Collectors.toList())).write(intervalSubsetReadCountFile);
            intervalSubsetReadCountFiles.add(intervalSubsetReadCountFile);
        }
        return intervalSubsetReadCountFiles;
    }

    private boolean executeGermlineCNVCallerPythonScript(final List<File> intervalSubsetReadCountFiles) {
        final PythonScriptExecutor executor = new PythonScriptExecutor(true);
        final String outputDirArg = Utils.nonEmpty(outputDir).endsWith(File.separator)
                ? outputDir
                : outputDir + File.separator;    //add trailing slash if necessary

        //add required arguments
        final List<String> arguments = new ArrayList<>(Arrays.asList(
                "--ploidy_calls_path=" + inputContigPloidyCallsDir,
                "--output_calls_path=" + outputDirArg + outputPrefix + CALLS_PATH_SUFFIX,
                "--output_tracking_path=" + outputDirArg + outputPrefix + TRACKING_PATH_SUFFIX));

        //if a model path is given, add it to the argument (both COHORT and CASE modes)
        if (inputModelDir != null) {
            arguments.add("--input_model_path=" + inputModelDir);
        }

        final String script;
        if (runMode == RunMode.COHORT) {
            script = COHORT_DENOISING_CALLING_PYTHON_SCRIPT;
            //these are the annotated intervals, if provided
            arguments.add("--modeling_interval_list=" + specifiedIntervalsFile.getAbsolutePath());
            arguments.add("--output_model_path=" + outputDirArg + outputPrefix + MODEL_PATH_SUFFIX);
            if (inputAnnotatedIntervalsFile != null) {
                arguments.add("--enable_explicit_gc_bias_modeling=True");
            } else {
                arguments.add("--enable_explicit_gc_bias_modeling=False");
            }
        } else {
            script = CASE_SAMPLE_CALLING_PYTHON_SCRIPT;
            // in the case mode, explicit gc bias modeling is set by the model
        }

        arguments.add("--read_count_tsv_files");
        arguments.addAll(intervalSubsetReadCountFiles.stream().map(File::getAbsolutePath).collect(Collectors.toList()));

        arguments.addAll(germlineDenoisingModelArgumentCollection.generatePythonArguments(runMode));
        arguments.addAll(germlineCallingArgumentCollection.generatePythonArguments(runMode));
        arguments.addAll(germlineCNVHybridADVIArgumentCollection.generatePythonArguments());

        return executor.executeScript(
                new Resource(script, GermlineCNVCaller.class),
                null,
                arguments);
    }
}
