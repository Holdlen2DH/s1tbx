/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.s1tbx.calibration.gpf.support.CalibrationFactory;
import org.esa.s1tbx.calibration.gpf.support.Calibrator;
import org.esa.s1tbx.calibration.gpf.CalibrationOp;
import org.esa.s1tbx.calibration.gpf.Sentinel1Calibrator;
import org.esa.s1tbx.insar.gpf.geometric.SARGeocoding;
import org.esa.s1tbx.insar.gpf.geometric.SARUtils;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.dem.dataio.EarthGravitationalModel96;
import org.esa.snap.dem.dataio.FileElevationModel;
import org.esa.snap.datamodel.AbstractMetadata;
import org.esa.snap.datamodel.OrbitStateVector;
import org.esa.snap.datamodel.PosVector;
import org.esa.snap.datamodel.Unit;
import org.esa.snap.eo.Constants;
import org.esa.snap.eo.GeoUtils;
import org.esa.snap.eo.LocalGeometry;
import org.esa.snap.framework.datamodel.Band;
import org.esa.snap.framework.datamodel.GeoCoding;
import org.esa.snap.framework.datamodel.GeoPos;
import org.esa.snap.framework.datamodel.MetadataAttribute;
import org.esa.snap.framework.datamodel.MetadataElement;
import org.esa.snap.framework.datamodel.PixelPos;
import org.esa.snap.framework.datamodel.Product;
import org.esa.snap.framework.datamodel.ProductData;
import org.esa.snap.framework.datamodel.TiePointGrid;
import org.esa.snap.framework.dataop.dem.ElevationModel;
import org.esa.snap.framework.dataop.resamp.Resampling;
import org.esa.snap.framework.dataop.resamp.ResamplingFactory;
import org.esa.snap.framework.gpf.Operator;
import org.esa.snap.framework.gpf.OperatorException;
import org.esa.snap.framework.gpf.OperatorSpi;
import org.esa.snap.framework.gpf.Tile;
import org.esa.snap.framework.gpf.annotations.OperatorMetadata;
import org.esa.snap.framework.gpf.annotations.Parameter;
import org.esa.snap.framework.gpf.annotations.SourceProduct;
import org.esa.snap.framework.gpf.annotations.TargetProduct;
import org.esa.snap.gpf.InputProductValidator;
import org.esa.snap.gpf.OperatorUtils;
import org.esa.snap.gpf.ReaderUtils;
import org.esa.snap.gpf.TileGeoreferencing;
import org.esa.snap.util.ProductUtils;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw SAR images usually contain significant geometric distortions. One of the factors that cause the
 * distortions is the ground elevation of the targets. This operator corrects the topographic distortion
 * in the raw image caused by this factor. The operator implements the Range-Doppler (RD) geocoding method.
 * <p/>
 * The method consis of the following major steps:
 * (1) Get state vectors from the metadata;
 * (2) Compute satellite position and velocity for each azimuth time by interpolating the state vectors;
 * (3) Get corner latitudes and longitudes for the source image;
 * (4) Compute [LatMin, LatMax] and [LonMin, LonMax];
 * (5) Get the range and azimuth spacings for the source image;
 * (6) Compute DEM traversal sample intervals (delLat, delLon) based on source image pixel spacing;
 * (7) Compute target geocoded image dimension;
 * (8) Repeat the following steps for each sample in the target raster [LatMax:-delLat:LatMin]x[LonMin:delLon:LonMax]:
 * (8.1) Get local elevation h(i,j) for current sample given local latitude lat(i,j) and longitude lon(i,j);
 * (8.2) Convert (lat(i,j), lon(i,j), h(i,j)) to global Cartesian coordinates p(Px, Py, Pz);
 * (8.3) Compute zero Doppler time t(i,j) for point p(Px, Py, Pz) using Doppler frequency function;
 * (8.4) Compute satellite position s(i,j) and slant range r(i,j) = |s(i,j) - p| for zero Doppler time t(i,j);
 * (8.5) Compute bias-corrected zero Doppler time tc(i,j) = t(i,j) + r(i,j)*2/c, where c is the light speed;
 * (8.6) Update satellite position s(tc(i,j)) and slant range r(tc(i,j)) = |s(tc(i,j)) - p| for time tc(i,j);
 * (8.7) Compute azimuth image index Ia using zero Doppler time tc(i,j);
 * (8.8) Compute range image index Ir using slant range r(tc(i,j)) or ground range;
 * (8.9) Compute pixel value x(Ia,Ir) using interpolation and save it for current sample.
 * <p/>
 * Reference: Guide to ASAR Geocoding, Issue 1.0, 19.03.2008
 */

@OperatorMetadata(alias = "Terrain-Correction",
        category = "SAR Processing/Geometric/Terrain Correction",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "RD method for orthorectification")
public class RangeDopplerGeocodingOp extends Operator {

    public static final String PRODUCT_SUFFIX = "_TC";

    @SourceProduct(alias = "source")
    Product sourceProduct;
    @TargetProduct
    Product targetProduct;

    @Parameter(description = "The list of source bands.", alias = "sourceBands", itemAlias = "band",
            rasterDataNodeType = Band.class, label = "Source Bands")
    private
    String[] sourceBandNames = null;

    @Parameter(valueSet = {"ACE", "GETASSE30", "SRTM 3Sec", "ASTER 1sec GDEM"}, description = "The digital elevation model.",
            defaultValue = "SRTM 3Sec", label = "Digital Elevation Model")
    private String demName = "SRTM 3Sec";

    @Parameter(label = "External DEM")
    private File externalDEMFile = null;

    @Parameter(label = "DEM No Data Value", defaultValue = "0")
    private double externalDEMNoDataValue = 0;

    @Parameter(valueSet = {ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
            ResamplingFactory.BILINEAR_INTERPOLATION_NAME, ResamplingFactory.CUBIC_CONVOLUTION_NAME},
            defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME, label = "DEM Resampling Method")
    private String demResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(valueSet = {ResamplingFactory.NEAREST_NEIGHBOUR_NAME,
            ResamplingFactory.BILINEAR_INTERPOLATION_NAME, ResamplingFactory.CUBIC_CONVOLUTION_NAME},
            defaultValue = ResamplingFactory.BILINEAR_INTERPOLATION_NAME, label = "Image Resampling Method")
    private String imgResamplingMethod = ResamplingFactory.BILINEAR_INTERPOLATION_NAME;

    @Parameter(description = "The pixel spacing in meters", defaultValue = "0", label = "Pixel Spacing (m)")
    private double pixelSpacingInMeter = 0;

    @Parameter(description = "The pixel spacing in degrees", defaultValue = "0", label = "Pixel Spacing (deg)")
    private double pixelSpacingInDegree = 0;

    @Parameter(description = "The coordinate reference system in well known text format", defaultValue = "WGS84(DD)")
    private String mapProjection = "WGS84(DD)";

    @Parameter(defaultValue = "true", label = "Mask out areas with no elevation", description = "Mask the sea with no data value (faster)")
    private boolean nodataValueAtSea = true;

    @Parameter(defaultValue = "false", label = "Save DEM as band")
    private boolean saveDEM = false;

    @Parameter(defaultValue = "false", label = "Save latitude and longitude as band")
    private boolean saveLatLon = false;

    @Parameter(defaultValue = "false", label = "Save incidence angle from ellipsoid as band")
    private boolean saveIncidenceAngleFromEllipsoid = false;

    @Parameter(defaultValue = "false", label = "Save local incidence angle as band")
    private boolean saveLocalIncidenceAngle = false;

    @Parameter(defaultValue = "false", label = "Save projected local incidence angle as band")
    private boolean saveProjectedLocalIncidenceAngle = false;

    @Parameter(defaultValue = "true", label = "Save selected source band")
    private boolean saveSelectedSourceBand = true;

    @Parameter(defaultValue = "false", label = "Apply radiometric normalization")
    private boolean applyRadiometricNormalization = false;

    @Parameter(defaultValue = "false", label = "Save Sigma0 as a band")
    private boolean saveSigmaNought = false;

    @Parameter(defaultValue = "false", label = "Save Gamma0 as a band")
    private boolean saveGammaNought = false;

    @Parameter(defaultValue = "false", label = "Save Beta0 as a band")
    private boolean saveBetaNought = false;

    @Parameter(valueSet = {Constants.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID, Constants.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM,
            Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM},
            defaultValue = Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM, label = "")
    private String incidenceAngleForSigma0 = Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM;

    @Parameter(valueSet = {Constants.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID, Constants.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM,
            Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM},
            defaultValue = Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM, label = "")
    private String incidenceAngleForGamma0 = Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM;

    @Parameter(valueSet = {CalibrationOp.LATEST_AUX, CalibrationOp.PRODUCT_AUX, CalibrationOp.EXTERNAL_AUX},
            description = "The auxiliary file", defaultValue = CalibrationOp.LATEST_AUX, label = "Auxiliary File")
    private String auxFile = CalibrationOp.LATEST_AUX;

    @Parameter(description = "The antenne elevation pattern gain auxiliary data file.", label = "External Aux File")
    private File externalAuxFile = null;

    private MetadataElement absRoot = null;
    private ElevationModel dem = null;
    private Band elevationBand = null;
    private double demNoDataValue = 0.0f; // no data value for DEM
    private GeoCoding targetGeoCoding = null;

    private boolean srgrFlag = false;
    private boolean isElevationModelAvailable = false;
    private boolean usePreCalibrationOp = false;

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;
    private int targetImageWidth = 0;
    private int targetImageHeight = 0;

    private double avgSceneHeight = 0.0; // in m
    private double wavelength = 0.0; // in m
    private double rangeSpacing = 0.0;
    private double azimuthSpacing = 0.0;
    private double firstLineUTC = 0.0; // in days
    private double lastLineUTC = 0.0; // in days
    private double lineTimeInterval = 0.0; // in days
    private double nearEdgeSlantRange = 0.0; // in m

    private CoordinateReferenceSystem targetCRS;
    private double delLat = 0.0;
    private double delLon = 0.0;
    private SARGeocoding.Orbit orbit = null;

    private AbstractMetadata.SRGRCoefficientList[] srgrConvParams = null;
    private OrbitStateVector[] orbitStateVectors = null;
    private final HashMap<String, Band[]> targetBandNameToSourceBand = new HashMap<>();
    private final Map<String, Boolean> targetBandApplyRadiometricNormalizationFlag = new HashMap<>();
    private final Map<String, Boolean> targetBandApplyRetroCalibrationFlag = new HashMap<>();
    private TiePointGrid incidenceAngle = null;
    private TiePointGrid latitude = null;
    private TiePointGrid longitude = null;
    private static final int INVALID_SUB_SWATH_INDEX = -1;

    private Resampling imgResampling = null;

    boolean useAvgSceneHeight = false;
    private Calibrator calibrator = null;
    private boolean orthoDataProduced = false;  // check if any ortho data is actually produced
    private boolean processingStarted = false;
    private boolean isPolsar = false;

    private boolean nearRangeOnLeft = true; // temp fix for descending Radarsat2
    private String mission = null;
    private boolean skipBistaticCorrection = false;

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.snap.framework.datamodel.Product} annotated with the
     * {@link org.esa.snap.framework.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.snap.framework.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            final InputProductValidator validator = new InputProductValidator(sourceProduct);
            validator.checkIfMapProjected();
            validator.checkIfTOPSARBurstProduct(false);

            checkUserInput();

            getSourceImageDimension();

            getMetadata();

            getTiePointGrid();

            if (useAvgSceneHeight) {
                saveSigmaNought = false;
                saveBetaNought = false;
                saveGammaNought = false;
                saveDEM = false;
                saveLocalIncidenceAngle = false;
                saveProjectedLocalIncidenceAngle = false;
            }

            imgResampling = ResamplingFactory.createResampling(imgResamplingMethod);

            createTargetProduct();

            computeSensorPositionsAndVelocities();

            if (saveSigmaNought) {
                calibrator = CalibrationFactory.createCalibrator(sourceProduct);

                if (calibrator instanceof Sentinel1Calibrator) {
                    final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);
                    final Set<String> polList = new HashSet<>();
                    for(Band band : sourceBands) {
                        polList.add(OperatorUtils.getBandPolarization(band.getName(), absRoot));
                    }
                    final String[] selectedPolarisations = polList.toArray(new String[polList.size()]);

                    Sentinel1Calibrator cal = (Sentinel1Calibrator) calibrator;
                    cal.setUserSelections(sourceProduct,
                            selectedPolarisations, saveSigmaNought, saveGammaNought, saveBetaNought, false);
                }

                calibrator.setAuxFileFlag(auxFile);
                calibrator.setExternalAuxFile(externalAuxFile);
                calibrator.initialize(this, sourceProduct, targetProduct, true, true);
                calibrator.setIncidenceAngleForSigma0(incidenceAngleForSigma0);
            }

            updateTargetProductMetadata();

            if (externalDEMFile == null && !useAvgSceneHeight) {
                DEMFactory.checkIfDEMInstalled(demName);
            }

            if (!useAvgSceneHeight) {
                DEMFactory.validateDEM(demName, sourceProduct);
            }
        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    @Override
    public void dispose() throws OperatorException {
        if (dem != null) {
            dem.dispose();
        }

        if (!orthoDataProduced && processingStarted) {
            final String errMsg = getId() + " error: no valid output was produced. Please verify the DEM";
            System.out.println(errMsg);
            //if (VisatApp.getApp() != null) {
            //    VisatApp.getApp().setStatusBarMessage(errMsg);
            //}
        }
    }

    private void checkUserInput() {

        if (!saveSelectedSourceBand && !applyRadiometricNormalization) {
            throw new OperatorException("Please select output band for terrain corrected image");
        }

        if (!applyRadiometricNormalization) {
            saveSigmaNought = false;
            saveGammaNought = false;
            saveBetaNought = false;
        }

        if (saveBetaNought || saveGammaNought ||
                (saveSigmaNought && incidenceAngleForSigma0.contains(Constants.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID)) ||
                (saveSigmaNought && incidenceAngleForSigma0.contains(Constants.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM))) {
            saveSigmaNought = true;
            saveProjectedLocalIncidenceAngle = true;
        }

        if ((saveGammaNought && incidenceAngleForGamma0.contains(Constants.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID)) ||
                (saveSigmaNought && incidenceAngleForSigma0.contains(Constants.USE_INCIDENCE_ANGLE_FROM_ELLIPSOID))) {
            saveIncidenceAngleFromEllipsoid = true;
        }

        if ((saveGammaNought && incidenceAngleForGamma0.contains(Constants.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM)) ||
                (saveSigmaNought && incidenceAngleForSigma0.contains(Constants.USE_LOCAL_INCIDENCE_ANGLE_FROM_DEM))) {
            saveLocalIncidenceAngle = true;
        }

        incidenceAngle = OperatorUtils.getIncidenceAngle(sourceProduct);
    }

    private void getTiePointGrid() {
        latitude = OperatorUtils.getLatitude(sourceProduct);
        if (latitude == null) {
            throw new OperatorException("Product without latitude tie point grid");
        }

        longitude = OperatorUtils.getLongitude(sourceProduct);
        if (longitude == null) {
            throw new OperatorException("Product without longitude tie point grid");
        }
    }

    /**
     * Retrieve required data from Abstracted Metadata
     *
     * @throws Exception if metadata not found
     */
    private void getMetadata() throws Exception {
        absRoot = AbstractMetadata.getAbstractedMetadata(sourceProduct);

        mission = getMissionType(absRoot);

        if (mission.contains("CSKS") || mission.contains("TSX") || mission.equals("RS2") || mission.contains("SENTINEL")) {
            skipBistaticCorrection = true;
        }

        srgrFlag = AbstractMetadata.getAttributeBoolean(absRoot, AbstractMetadata.srgr_flag);

        wavelength = SARUtils.getRadarFrequency(absRoot);

        rangeSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.range_spacing);
        if (rangeSpacing <= 0.0) {
            throw new OperatorException("Invalid input for range pixel spacing: " + rangeSpacing);
        }

        azimuthSpacing = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.azimuth_spacing);
        if (azimuthSpacing <= 0.0) {
            throw new OperatorException("Invalid input for azimuth pixel spacing: " + azimuthSpacing);
        }

        firstLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.first_line_time)).getMJD(); // in days
        lastLineUTC = AbstractMetadata.parseUTC(absRoot.getAttributeString(AbstractMetadata.last_line_time)).getMJD(); // in days
        lineTimeInterval = (lastLineUTC - firstLineUTC) / (sourceImageHeight - 1); // in days
        if (lineTimeInterval == 0.0) {
            throw new OperatorException("Invalid input for Line Time Interval: " + lineTimeInterval);
        }

        orbitStateVectors = AbstractMetadata.getOrbitStateVectors(absRoot);
        if (orbitStateVectors == null || orbitStateVectors.length == 0) {
            throw new OperatorException("Invalid Obit State Vectors");
        }

        if (srgrFlag) {
            srgrConvParams = AbstractMetadata.getSRGRCoefficients(absRoot);
            if (srgrConvParams == null || srgrConvParams.length == 0) {
                throw new OperatorException("Invalid SRGR Coefficients");
            }
        } else {
            nearEdgeSlantRange = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel);
        }

        // used for retro-calibration or when useAvgSceneHeight is true
        avgSceneHeight = AbstractMetadata.getAttributeDouble(absRoot, AbstractMetadata.avg_scene_height);

        MetadataAttribute attribute = absRoot.getAttribute("retro-calibration performed flag");
        if (attribute != null) {
            usePreCalibrationOp = true;
            if (!applyRadiometricNormalization) {
                throw new OperatorException("Apply radiometric normalization must be selected.");
            }
        } else {
            final boolean multilookFlag = AbstractMetadata.getAttributeBoolean(absRoot, AbstractMetadata.multilook_flag);
            if (applyRadiometricNormalization && (mission.equals("ERS1") || mission.equals("ERS2")) && !multilookFlag) {
                throw new OperatorException("For radiometric normalization of ERS product, please first use\n" +
                        "  'Remove Antenna Pattern' operator to remove calibration factors applied and apply ADC,\n" +
                        "  then apply 'Range-Doppler Terrain Correction' operator; or use one of the following\n" +
                        "  user graphs: 'RemoveAntPat_Orthorectify' or 'RemoveAntPat_Multilook_Orthorectify'.");
            }
        }

        nearRangeOnLeft = SARGeocoding.isNearRangeOnLeft(incidenceAngle, sourceImageWidth);

        isPolsar = absRoot.getAttributeInt(AbstractMetadata.polsarData, 0) == 1;
    }

    /**
     * Get the mission type.
     *
     * @param absRoot the AbstractMetadata
     * @return the mission string
     */
    public static String getMissionType(final MetadataElement absRoot) {
        final String mission = absRoot.getAttributeString(AbstractMetadata.MISSION);
        if (mission.equals("ALOS")) {
            final String productType = absRoot.getAttributeString(AbstractMetadata.PRODUCT_TYPE).toUpperCase();
            if (!productType.contains("1.1"))
                throw new OperatorException("Detected ALOS PALSAR products are currently not supported");
        }
        if (mission.equals("RS1")) {
            //throw new OperatorException("RadarSAT-1 product is currently not supported");
        }

        return mission;
    }

    /**
     * Get elevation model.
     *
     * @throws Exception The exceptions.
     */
    private synchronized void getElevationModel() throws Exception {

        if (isElevationModelAvailable) return;
        if (externalDEMFile != null) { // if external DEM file is specified by user

            dem = new FileElevationModel(externalDEMFile, demResamplingMethod, externalDEMNoDataValue);
            demNoDataValue = externalDEMNoDataValue;
            demName = externalDEMFile.getName();

        } else {

            dem = DEMFactory.createElevationModel(demName, demResamplingMethod);
            demNoDataValue = dem.getDescriptor().getNoDataValue();
        }

        if (elevationBand != null) {
            elevationBand.setNoDataValue(demNoDataValue);
            elevationBand.setNoDataValueUsed(true);
        }

        isElevationModelAvailable = true;
    }

    /**
     * Get source image width and height.
     */
    private void getSourceImageDimension() {
        sourceImageWidth = sourceProduct.getSceneRasterWidth();
        sourceImageHeight = sourceProduct.getSceneRasterHeight();
    }

    private void createTargetProduct() {
        try {
            if (pixelSpacingInMeter <= 0.0) {
                pixelSpacingInMeter = Math.max(SARGeocoding.getAzimuthPixelSpacing(sourceProduct),
                        SARGeocoding.getRangePixelSpacing(sourceProduct));
                pixelSpacingInDegree = SARGeocoding.getPixelSpacingInDegree(pixelSpacingInMeter);
            }
            delLat = pixelSpacingInDegree;
            delLon = pixelSpacingInDegree;

            final CRSGeoCodingHandler crsHandler = new CRSGeoCodingHandler(sourceProduct, mapProjection,
                    pixelSpacingInDegree, pixelSpacingInMeter);

            targetCRS = crsHandler.getTargetCRS();

            targetProduct = new Product(sourceProduct.getName() + PRODUCT_SUFFIX,
                    sourceProduct.getProductType(), crsHandler.getTargetWidth(), crsHandler.getTargetHeight());
            targetProduct.setGeoCoding(crsHandler.getCrsGeoCoding());

            targetImageWidth = targetProduct.getSceneRasterWidth();
            targetImageHeight = targetProduct.getSceneRasterHeight();

            addSelectedBands();

            targetGeoCoding = targetProduct.getGeoCoding();

            ProductUtils.copyMetadata(sourceProduct, targetProduct);
            ProductUtils.copyMasks(sourceProduct, targetProduct);
            ProductUtils.copyVectorData(sourceProduct, targetProduct);
            targetProduct.setStartTime(sourceProduct.getStartTime());
            targetProduct.setEndTime(sourceProduct.getEndTime());
            targetProduct.setDescription(sourceProduct.getDescription());

            try {
                ProductUtils.copyIndexCodings(sourceProduct, targetProduct);
            } catch (Exception e) {
                if (!imgResampling.equals(Resampling.NEAREST_NEIGHBOUR)) {
                    throw new OperatorException("Use Nearest Neighbour with Classifications: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    /**
     * Compute sensor position and velocity for each range line.
     */
    private void computeSensorPositionsAndVelocities() {

        orbit = new SARGeocoding.Orbit(orbitStateVectors, firstLineUTC, lineTimeInterval, sourceImageHeight);
    }

    /**
     * Add the user selected bands to target product.
     *
     * @throws OperatorException The exceptions.
     */
    void addSelectedBands() throws OperatorException {

        final Band[] sourceBands = OperatorUtils.getSourceBands(sourceProduct, sourceBandNames, false);

        String targetBandName;
        for (int i = 0; i < sourceBands.length; i++) {

            final Band srcBand = sourceBands[i];
            final String unit = srcBand.getUnit();

            if (unit != null && !isPolsar &&
                    (unit.equals(Unit.REAL) || unit.equals(Unit.IMAGINARY))) {

                if (i == sourceBands.length - 1) {
                    throw new OperatorException("Real and imaginary bands should be selected in pairs");
                }
                final String nextUnit = sourceBands[i + 1].getUnit();
                if (nextUnit == null || !((unit.equals(Unit.REAL) && nextUnit.equals(Unit.IMAGINARY)) ||
                        (unit.equals(Unit.IMAGINARY) && nextUnit.equals(Unit.REAL)))) {
                    throw new OperatorException("Real and imaginary bands should be selected in pairs");
                }
                final Band[] srcBands = new Band[2];
                srcBands[0] = srcBand;
                srcBands[1] = sourceBands[i + 1];
                final String pol = OperatorUtils.getBandPolarization(srcBand.getName(), absRoot);
                final String suffix = OperatorUtils.getSuffixFromBandName(srcBand.getName());

                if (saveSigmaNought) {
                    if (suffix != null && !suffix.isEmpty() && !isPolsar) {
                        if (pol != null && !pol.isEmpty() && !suffix.contains(pol) && !suffix.contains(pol.toUpperCase())) {
                            targetBandName = "Sigma0_" + suffix + '_' + pol.toUpperCase();
                        } else {
                            targetBandName = "Sigma0_" + suffix;
                        }
                    } else {
                        targetBandName = "Sigma0";
                    }
                    if (addTargetBand(targetBandName, Unit.INTENSITY, srcBand) != null) {
                        targetBandNameToSourceBand.put(targetBandName, srcBands);
                        targetBandApplyRadiometricNormalizationFlag.put(targetBandName, true);
                        if (usePreCalibrationOp) {
                            targetBandApplyRetroCalibrationFlag.put(targetBandName, false);
                        } else {
                            targetBandApplyRetroCalibrationFlag.put(targetBandName, true);
                        }
                    }
                }

                if (saveSelectedSourceBand) {
                    if (suffix != null && !suffix.isEmpty() && !isPolsar) {
                        if (pol != null && !pol.isEmpty() && !suffix.contains(pol) && !suffix.contains(pol.toUpperCase())) {
                            targetBandName = "Intensity_" + suffix + '_' + pol.toUpperCase();
                        } else {
                            targetBandName = "Intensity_" + suffix;
                        }
                    } else {
                        targetBandName = "Intensity";
                    }
                    if (addTargetBand(targetBandName, Unit.INTENSITY, srcBand) != null) {
                        targetBandNameToSourceBand.put(targetBandName, srcBands);
                        targetBandApplyRadiometricNormalizationFlag.put(targetBandName, false);
                        targetBandApplyRetroCalibrationFlag.put(targetBandName, false);
                    }
                }
                ++i;

            } else {

                final Band[] srcBands = {srcBand};
                final String pol = OperatorUtils.getBandPolarization(srcBand.getName(), absRoot);
                final String suffix = OperatorUtils.getSuffixFromBandName(srcBand.getName());
                if (saveSigmaNought) {
                    targetBandName = "Sigma0";
                    if (suffix != null && !suffix.isEmpty() && !isPolsar) {
                        if (pol != null && !pol.isEmpty() && !suffix.contains(pol) && !suffix.contains(pol.toUpperCase())) {
                            targetBandName += '_' + suffix + '_' + pol.toUpperCase();
                        } else {
                            targetBandName += '_' + suffix;
                        }
                    }
                    if (addTargetBand(targetBandName, Unit.INTENSITY, srcBand) != null) {
                        targetBandNameToSourceBand.put(targetBandName, srcBands);
                        targetBandApplyRadiometricNormalizationFlag.put(targetBandName, true);
                        if (usePreCalibrationOp) {
                            targetBandApplyRetroCalibrationFlag.put(targetBandName, false);
                        } else {
                            targetBandApplyRetroCalibrationFlag.put(targetBandName, true);
                        }
                    }
                }

                if (saveSelectedSourceBand) {
                    targetBandName = srcBand.getName();
                    if (pol != null && !pol.isEmpty() && !isPolsar && !srcBand.getName().toLowerCase().contains(pol)) {
                        targetBandName += '_' + pol.toUpperCase();
                    }
                    int dataType = ProductData.TYPE_FLOAT32;
                    // use original dataType for nearest neighbour and indexCoding bands
                    if (imgResampling.equals(Resampling.NEAREST_NEIGHBOUR))
                        dataType = srcBand.getDataType();
                    if (addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                            targetBandName, unit, srcBand, dataType) != null) {
                        targetBandNameToSourceBand.put(targetBandName, srcBands);
                        targetBandApplyRadiometricNormalizationFlag.put(targetBandName, false);
                        targetBandApplyRetroCalibrationFlag.put(targetBandName, false);
                    }
                }
            }
        }

        if (saveDEM) {
            elevationBand = addTargetBand("elevation", Unit.METERS, null);
        }

        if (saveLatLon) {
            addTargetBand("latitude", Unit.DEGREES, null);
            addTargetBand("longitude", Unit.DEGREES, null);
        }

        if (saveLocalIncidenceAngle) {
            addTargetBand("localIncidenceAngle", Unit.DEGREES, null);
        }

        if (saveProjectedLocalIncidenceAngle) {
            addTargetBand("projectedLocalIncidenceAngle", Unit.DEGREES, null);
        }

        if (saveIncidenceAngleFromEllipsoid) {
            addTargetBand("incidenceAngleFromEllipsoid", Unit.DEGREES, null);
        }

        if (saveSigmaNought && !incidenceAngleForSigma0.contains(Constants.USE_PROJECTED_INCIDENCE_ANGLE_FROM_DEM)) {
            CalibrationFactory.createSigmaNoughtVirtualBand(targetProduct, incidenceAngleForSigma0);
        }

        if (saveGammaNought) {
            CalibrationFactory.createGammaNoughtVirtualBand(targetProduct, incidenceAngleForGamma0);
        }

        if (saveBetaNought) {
            CalibrationFactory.createBetaNoughtVirtualBand(targetProduct);
        }
    }

    private Band addTargetBand(final String bandName, final String bandUnit, final Band sourceBand) {
        return addTargetBand(targetProduct, targetImageWidth, targetImageHeight,
                bandName, bandUnit, sourceBand, ProductData.TYPE_FLOAT32);
    }

    public static Band addTargetBand(final Product targetProduct, final int targetImageWidth, final int targetImageHeight,
                                     final String bandName, final String bandUnit, final Band sourceBand,
                                     final int dataType) {

        if (targetProduct.getBand(bandName) == null) {

            final Band targetBand = new Band(bandName, dataType, targetImageWidth, targetImageHeight);

            targetBand.setUnit(bandUnit);
            if (sourceBand != null) {
                targetBand.setDescription(sourceBand.getDescription());
                targetBand.setNoDataValue(sourceBand.getNoDataValue());
            }
            targetBand.setNoDataValueUsed(true);
            targetProduct.addBand(targetBand);
            return targetBand;
        }

        return null;
    }

    /**
     * Update metadata in the target product.
     *
     * @throws OperatorException The exception.
     */
    private void updateTargetProductMetadata() throws Exception {

        final MetadataElement absTgt = AbstractMetadata.getAbstractedMetadata(targetProduct);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.srgr_flag, 1);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_output_lines, targetImageHeight);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.num_samples_per_line, targetImageWidth);

        final GeoPos geoPosFirstNear = targetGeoCoding.getGeoPos(new PixelPos(0, 0), null);
        final GeoPos geoPosFirstFar = targetGeoCoding.getGeoPos(new PixelPos(targetImageWidth - 1, 0), null);
        final GeoPos geoPosLastNear = targetGeoCoding.getGeoPos(new PixelPos(0, targetImageHeight - 1), null);
        final GeoPos geoPosLastFar = targetGeoCoding.getGeoPos(new PixelPos(targetImageWidth - 1, targetImageHeight - 1), null);

        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_lat, geoPosFirstNear.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_lat, geoPosFirstFar.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_lat, geoPosLastNear.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_lat, geoPosLastFar.getLat());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_near_long, geoPosFirstNear.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.first_far_long, geoPosFirstFar.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_near_long, geoPosLastNear.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.last_far_long, geoPosLastFar.getLon());
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.TOT_SIZE, ReaderUtils.getTotalSize(targetProduct));
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.map_projection, targetCRS.getName().getCode());
        if (!useAvgSceneHeight) {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.is_terrain_corrected, 1);
            if (externalDEMFile != null) { // if external DEM file is specified by user
                AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, externalDEMFile.getPath());
            } else {
                AbstractMetadata.setAttribute(absTgt, AbstractMetadata.DEM, demName);
            }
        }

        // map projection too
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.geo_ref_system, "WGS84");
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.lat_pixel_res, delLat);
        AbstractMetadata.setAttribute(absTgt, AbstractMetadata.lon_pixel_res, delLon);

        if (pixelSpacingInMeter > 0.0 &&
                Double.compare(pixelSpacingInMeter, SARGeocoding.getPixelSpacing(sourceProduct)) != 0) {
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.range_spacing, pixelSpacingInMeter);
            AbstractMetadata.setAttribute(absTgt, AbstractMetadata.azimuth_spacing, pixelSpacingInMeter);
        }

        // save look directions for 5 range lines
        final MetadataElement lookDirectionListElem = new MetadataElement("Look_Direction_List");
        final int numOfDirections = 5;
        for (int i = 1; i <= numOfDirections; ++i) {
            SARGeocoding.addLookDirection("look_direction", lookDirectionListElem, i, numOfDirections, sourceImageWidth,
                    sourceImageHeight, firstLineUTC, lineTimeInterval, nearRangeOnLeft, latitude, longitude);
        }
        absTgt.addElement(lookDirectionListElem);
    }

    /**
     * Called by the framework in order to compute the stack of tiles for the given target bands.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTiles     The current tiles to be computed for each target band.
     * @param targetRectangle The area in pixel coordinates to be computed (same for all rasters in <code>targetRasters</code>).
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws OperatorException if an error occurs during computation of the target rasters.
     */
    @Override
    public void computeTileStack(Map<Band, Tile> targetTiles, Rectangle targetRectangle, ProgressMonitor pm) throws OperatorException {

        try {
            processingStarted = true;
            try {
                if (!isElevationModelAvailable) {
                    getElevationModel();
                }
            } catch (Exception e) {
                throw new OperatorException(e);
            }

            final int x0 = targetRectangle.x;
            final int y0 = targetRectangle.y;
            final int w = targetRectangle.width;
            final int h = targetRectangle.height;
            //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

            final TileGeoreferencing tileGeoRef = new TileGeoreferencing(targetProduct, x0 - 1, y0 - 1, w + 2, h + 2);

            double[][] localDEM = new double[h + 2][w + 2];
            if (useAvgSceneHeight) {
                DEMFactory.fillDEM(localDEM, avgSceneHeight);
            } else {
                final boolean valid = DEMFactory.getLocalDEM(
                        dem, demNoDataValue, demResamplingMethod, tileGeoRef, x0, y0, w, h, sourceProduct,
                        nodataValueAtSea, localDEM);
                if (!valid && nodataValueAtSea)
                    return;
            }

            final GeoPos geoPos = new GeoPos();
            final PosVector earthPoint = new PosVector();
            final PosVector sensorPos = new PosVector();
            final int srcMaxRange = sourceImageWidth - 1;
            final int srcMaxAzimuth = sourceImageHeight - 1;
            ProductData demBuffer = null;
            ProductData latBuffer = null;
            ProductData lonBuffer = null;
            ProductData localIncidenceAngleBuffer = null;
            ProductData projectedLocalIncidenceAngleBuffer = null;
            ProductData incidenceAngleFromEllipsoidBuffer = null;

            final List<TileData> trgTileList = new ArrayList<>();
            final Set<Band> keySet = targetTiles.keySet();
            for (Band targetBand : keySet) {

                if (targetBand.getName().equals("elevation")) {
                    demBuffer = targetTiles.get(targetBand).getDataBuffer();
                    continue;
                }

                if (targetBand.getName().equals("latitude")) {
                    latBuffer = targetTiles.get(targetBand).getDataBuffer();
                    continue;
                }

                if (targetBand.getName().equals("longitude")) {
                    lonBuffer = targetTiles.get(targetBand).getDataBuffer();
                    continue;
                }

                if (targetBand.getName().equals("localIncidenceAngle")) {
                    localIncidenceAngleBuffer = targetTiles.get(targetBand).getDataBuffer();
                    continue;
                }

                if (targetBand.getName().equals("projectedLocalIncidenceAngle")) {
                    projectedLocalIncidenceAngleBuffer = targetTiles.get(targetBand).getDataBuffer();
                    continue;
                }

                if (targetBand.getName().equals("incidenceAngleFromEllipsoid")) {
                    incidenceAngleFromEllipsoidBuffer = targetTiles.get(targetBand).getDataBuffer();
                    continue;
                }

                final Band[] srcBands = targetBandNameToSourceBand.get(targetBand.getName());

                final TileData td = new TileData(targetTiles.get(targetBand), srcBands, isPolsar,
                        targetBand.getName(), getBandUnit(targetBand.getName()), absRoot, calibrator, imgResampling);

                td.applyRadiometricNormalization = targetBandApplyRadiometricNormalizationFlag.get(targetBand.getName());
                td.applyRetroCalibration = targetBandApplyRetroCalibrationFlag.get(targetBand.getName());
                trgTileList.add(td);
            }

            final int maxY = y0 + h;
            final int maxX = x0 + w;
            final TileData[] trgTiles = trgTileList.toArray(new TileData[trgTileList.size()]);

            final EarthGravitationalModel96 egm = EarthGravitationalModel96.instance();

            int diffLat = Math.abs(latitude.getPixelInt(0,0) - latitude.getPixelInt(0,targetImageHeight));

            for (int y = y0; y < maxY; y++) {
                final int yy = y - y0 + 1;

                for (int x = x0; x < maxX; x++) {

                    final int index = trgTiles[0].targetTile.getDataBufferIndex(x, y);

                    double alt = localDEM[yy][x - x0 + 1];

                    if (alt == demNoDataValue && !useAvgSceneHeight) {
                        if (nodataValueAtSea) {
                            //saveNoDataValueToTarget(index, trgTiles);
                            continue;
                        }
                    }

                    tileGeoRef.getGeoPos(x, y, geoPos);
                    final double lat = geoPos.lat;
                    double lon = geoPos.lon;
                    if (lon >= 180.0) {
                        lon -= 360.0;
                    }

                    if (alt == demNoDataValue && !nodataValueAtSea) {
                        // get corrected elevation for 0
                        alt = egm.getEGM(lat, lon);
                    }

                    GeoUtils.geo2xyzWGS84(lat, lon, alt, earthPoint);

                    double zeroDopplerTime = SARGeocoding.getEarthPointZeroDopplerTime(firstLineUTC,
                            lineTimeInterval, wavelength, earthPoint, orbit.sensorPosition, orbit.sensorVelocity);

                    if (Double.compare(zeroDopplerTime, SARGeocoding.NonValidZeroDopplerTime) == 0) {
                        if (saveDEM) {
                            demBuffer.setElemDoubleAt(index, demNoDataValue);
                        }
                        continue;
                    }

                    double slantRange = SARGeocoding.computeSlantRange(zeroDopplerTime, orbit, earthPoint, sensorPos);

                    if (!skipBistaticCorrection) {
                        // skip bistatic correction for COSMO, TerraSAR-X and RadarSAT-2
                        zeroDopplerTime += slantRange / Constants.lightSpeedInMetersPerDay;
                        slantRange = SARGeocoding.computeSlantRange(zeroDopplerTime, orbit, earthPoint, sensorPos);
                    }

                    double rangeIndex = SARGeocoding.computeRangeIndex(srgrFlag, sourceImageWidth, firstLineUTC, lastLineUTC,
                            rangeSpacing, zeroDopplerTime, slantRange, nearEdgeSlantRange, srgrConvParams);

                    if (rangeIndex == -1.0) {
                        if (saveDEM) {
                            demBuffer.setElemDoubleAt(index, demNoDataValue);
                        }
                        continue;
                    }

                    // the following check will be removed if no product of any mission is read with near range on right
                    if (!nearRangeOnLeft) {
                        rangeIndex = srcMaxRange - rangeIndex;
                    }

                    final double azimuthIndex = (zeroDopplerTime - firstLineUTC) / lineTimeInterval;

                    if (!SARGeocoding.isValidCell(rangeIndex, azimuthIndex, lat, lon, diffLat, latitude, longitude,
                            srcMaxRange, srcMaxAzimuth, sensorPos)) {
                        if (saveDEM) {
                            demBuffer.setElemDoubleAt(index, demNoDataValue);
                        }
                    } else {
                        final double[] localIncidenceAngles = {SARGeocoding.NonValidIncidenceAngle, SARGeocoding.NonValidIncidenceAngle};
                        if (saveLocalIncidenceAngle || saveProjectedLocalIncidenceAngle || saveSigmaNought) {

                            final LocalGeometry localGeometry = new LocalGeometry(x, y, tileGeoRef, earthPoint, sensorPos);

                            SARGeocoding.computeLocalIncidenceAngle(
                                    localGeometry, demNoDataValue, saveLocalIncidenceAngle, saveProjectedLocalIncidenceAngle,
                                    saveSigmaNought, x0, y0, x, y, localDEM, localIncidenceAngles); // in degrees

                            if (saveLocalIncidenceAngle && localIncidenceAngles[0] != SARGeocoding.NonValidIncidenceAngle) {
                                localIncidenceAngleBuffer.setElemDoubleAt(index, localIncidenceAngles[0]);
                            }

                            if (saveProjectedLocalIncidenceAngle && localIncidenceAngles[1] != SARGeocoding.NonValidIncidenceAngle) {
                                projectedLocalIncidenceAngleBuffer.setElemDoubleAt(index, localIncidenceAngles[1]);
                            }
                        }

                        if (saveDEM) {
                            demBuffer.setElemDoubleAt(index, alt);
                        }
                        if (saveLatLon) {
                            latBuffer.setElemDoubleAt(index, lat);
                            lonBuffer.setElemDoubleAt(index, lon);
                        }

                        if (saveIncidenceAngleFromEllipsoid && incidenceAngle != null) {
                            incidenceAngleFromEllipsoidBuffer.setElemDoubleAt(
                                    index, incidenceAngle.getPixelDouble(rangeIndex, azimuthIndex));
                        }

                        double satelliteHeight = 0;
                        double sceneToEarthCentre = 0;
                        if (saveSigmaNought) {
                            satelliteHeight = Math.sqrt(
                                    sensorPos.x * sensorPos.x + sensorPos.y * sensorPos.y + sensorPos.z * sensorPos.z);

                            sceneToEarthCentre = Math.sqrt(
                                    earthPoint.x * earthPoint.x + earthPoint.y * earthPoint.y + earthPoint.z * earthPoint.z);
                        }

                        for (TileData tileData : trgTiles) {
                            int[] subSwathIndex = {INVALID_SUB_SWATH_INDEX};
                            double v = getPixelValue(azimuthIndex, rangeIndex, tileData, subSwathIndex);

                            if (v != tileData.noDataValue && tileData.applyRadiometricNormalization) {
                                if (localIncidenceAngles[1] != SARGeocoding.NonValidIncidenceAngle) {
                                    v = calibrator.applyCalibration(
                                            v, rangeIndex, azimuthIndex, slantRange, satelliteHeight, sceneToEarthCentre,
                                            localIncidenceAngles[1], tileData.bandPolar, tileData.bandUnit, subSwathIndex); // use projected incidence angle
                                } else {
                                    v = tileData.noDataValue;
                                }
                            }

                            tileData.tileDataBuffer.setElemDoubleAt(index, v);
                        }
                        orthoDataProduced = true;
                    }
                }
            }
            localDEM = null;

        } catch (Throwable e) {
            orthoDataProduced = true; //to prevent multiple error messages
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Get unit for the source band corresponding to the given target band.
     *
     * @param bandName The target band name.
     * @return The source band unit.
     */
    private Unit.UnitType getBandUnit(String bandName) {
        final Band[] srcBands = targetBandNameToSourceBand.get(bandName);
        return Unit.getUnitType(srcBands[0]);
    }

    /**
     * Compute orthorectified pixel value for given pixel.
     *
     * @param azimuthIndex  The azimuth index for pixel in source image.
     * @param rangeIndex    The range index for pixel in source image.
     * @param tileData      The source tile information.
     * @param subSwathIndex The subSwath index.
     * @return The pixel value.
     */
    private double getPixelValue(final double azimuthIndex, final double rangeIndex,
                                 final TileData tileData, final int[] subSwathIndex) {

        try {
            final int x0 = (int) (rangeIndex + 0.5);
            final int y0 = (int) (azimuthIndex + 0.5);
            Rectangle srcRect;

            if (imgResampling == Resampling.BILINEAR_INTERPOLATION) {

                srcRect = new Rectangle(Math.max(0, x0 - 1), Math.max(0, y0 - 1), 3, 3);

            } else if (imgResampling == Resampling.NEAREST_NEIGHBOUR) {

                srcRect = new Rectangle(x0, y0, 1, 1);

            } else if (imgResampling == Resampling.CUBIC_CONVOLUTION) {

                srcRect = new Rectangle(Math.max(0, x0 - 2), Math.max(0, y0 - 2), 5, 5);

            } else if (imgResampling == Resampling.BISINC_5_POINT_INTERPOLATION) {

                srcRect = new Rectangle(Math.max(0, x0 - 3), Math.max(0, y0 - 3), 6, 6);

            } else if (imgResampling == Resampling.BISINC_11_POINT_INTERPOLATION) {

                srcRect = new Rectangle(Math.max(0, x0 - 6), Math.max(0, y0 - 6), 12, 12);

            } else if (imgResampling == Resampling.BISINC_21_POINT_INTERPOLATION) {

                srcRect = new Rectangle(Math.max(0, x0 - 11), Math.max(0, y0 - 11), 22, 22);

            } else if (imgResampling == Resampling.BICUBIC_INTERPOLATION) {

                srcRect = new Rectangle(Math.max(0, x0 - 2), Math.max(0, y0 - 2), 5, 5);

            } else {
                throw new OperatorException("Unhandled interpolation method");
            }

            final Band[] srcBands = targetBandNameToSourceBand.get(tileData.bandName);
            tileData.imgResamplingRaster.set(rangeIndex, azimuthIndex, getSourceTile(srcBands[0], srcRect),
                    srcBands.length > 1 ? getSourceTile(srcBands[1], srcRect) : null);

            imgResampling.computeIndex(rangeIndex + 0.5, azimuthIndex + 0.5,
                    sourceImageWidth, sourceImageHeight, tileData.imgResamplingIndex);

            double v = imgResampling.resample(tileData.imgResamplingRaster, tileData.imgResamplingIndex);

            subSwathIndex[0] = tileData.imgResamplingRaster.getSubSwathIndex();

            return v;

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }

        return 0;
    }

    /**
     * Set flag for radiometric correction. This function is for unit test only.
     *
     * @param flag The flag.
     */
    void setApplyRadiometricCalibration(final boolean flag) {
        saveSelectedSourceBand = !flag;
        applyRadiometricNormalization = flag;
        saveSigmaNought = flag;
    }

    void setSourceBandNames(final String[] names) {
        sourceBandNames = names;
    }

    public static class TileData {
        final Tile targetTile;
        final ProductData tileDataBuffer;
        final String bandName;
        final String bandPolar;
        final Unit.UnitType bandUnit;
        final double noDataValue;
        final Band[] srcBands;
        final boolean isPolsar;
        private final Calibrator calibrator;
        boolean applyRadiometricNormalization = false;
        boolean applyRetroCalibration = false;
        final boolean computeIntensity;

        final ResamplingRaster imgResamplingRaster;
        final Resampling.Index imgResamplingIndex;

        public TileData(final Tile tile, final Band[] srcBands, final boolean isPolsar, final String name,
                        final Unit.UnitType unit, final MetadataElement absRoot, final Calibrator calibrator,
                        final Resampling imgResampling) {
            this.targetTile = tile;
            this.tileDataBuffer = tile.getDataBuffer();
            this.bandName = name;
            this.srcBands = srcBands;
            this.isPolsar = isPolsar;
            this.noDataValue = srcBands[0].getNoDataValue();
            this.bandPolar = OperatorUtils.getBandPolarization(srcBands[0].getName(), absRoot);
            this.bandUnit = unit;
            this.calibrator = calibrator;
            this.computeIntensity = !isPolsar &&
                    (bandUnit == Unit.UnitType.REAL || bandUnit == Unit.UnitType.IMAGINARY);

            this.imgResamplingRaster = new ResamplingRaster(this);
            imgResamplingIndex = imgResampling.createIndex();
        }
    }

    public static class ResamplingRaster implements Resampling.Raster {

        private double rangeIndex, azimuthIndex;
        private final TileData tileData;
        private Tile sourceTileI;
        private ProductData dataBufferI, dataBufferQ;
        private int subSwathIndex;

        public ResamplingRaster(final TileData tileData) {

            this.tileData = tileData;
        }

        public void set(final double rangeIndex, final double azimuthIndex,
                        final Tile sourceTileI, final Tile sourceTileQ) {
            this.rangeIndex = rangeIndex;
            this.azimuthIndex = azimuthIndex;

            this.sourceTileI = sourceTileI;

            this.dataBufferI = sourceTileI.getDataBuffer();
            if (sourceTileQ != null) {
                this.dataBufferQ = sourceTileQ.getDataBuffer();
            } else {
                this.dataBufferQ = null;
            }
        }

        public final int getWidth() {
            return sourceTileI.getWidth();
        }

        public final int getHeight() {
            return sourceTileI.getHeight();
        }

        public boolean getSamples(final int[] x, final int[] y, final double[][] samples) {

            final int[][] subSwathIndices = new int[y.length][x.length];
            boolean allPixelsFromSameSubSwath = true;
            boolean allValid = true;

            for (int i = 0; i < y.length; i++) {
                for (int j = 0; j < x.length; j++) {

                    final int index = sourceTileI.getDataBufferIndex(x[j], y[i]);
                    double v = dataBufferI.getElemDoubleAt(index);
                    if (tileData.noDataValue != 0 && (v == tileData.noDataValue)) {
                        samples[i][j] = tileData.noDataValue;
                        allValid = false;
                        continue;
                    }

                    samples[i][j] = v;

                    if (tileData.computeIntensity) {

                        final double vq = dataBufferQ.getElemDoubleAt(index);
                        if (tileData.noDataValue != 0 && vq == tileData.noDataValue) {
                            samples[i][j] = tileData.noDataValue;
                            allValid = false;
                            continue;
                        }

                        samples[i][j] = v * v + vq * vq;
                    }

                    final int[] subSwathIndex = {-1};
                    if (tileData.applyRetroCalibration) {
                        samples[i][j] = tileData.calibrator.applyRetroCalibration(
                                x[j], y[i], samples[i][j], tileData.bandPolar, tileData.bandUnit, subSwathIndex);

                        subSwathIndices[i][j] = subSwathIndex[0];
                        if (subSwathIndex[0] != subSwathIndices[0][0]) {
                            allPixelsFromSameSubSwath = false;
                        }
                    }
                }
            }

            if (allPixelsFromSameSubSwath) {
                this.subSwathIndex = subSwathIndices[0][0];

            } else {

                int xIdx = -1, yIdx = -1;
                for (int i = 0; i < y.length; i++) {
                    if (Math.abs(azimuthIndex - y[i]) <= 0.5) {
                        yIdx = i;
                        break;
                    }
                }

                for (int j = 0; j < x.length; j++) {
                    if (Math.abs(rangeIndex - x[j]) <= 0.5) {
                        xIdx = j;
                        break;
                    }
                }

                if (xIdx != -1 && yIdx != -1) {
                    this.subSwathIndex = subSwathIndices[yIdx][xIdx];
                    double sample = samples[yIdx][xIdx];
                    for (int i = 0; i < y.length; i++) {
                        for (int j = 0; j < x.length; j++) {
                            samples[i][j] = sample;
                        }
                    }
                } else {
                    throw new OperatorException("Invalid x and y input for getSamples");
                }
            }
            return allValid;
        }

        public int getSubSwathIndex() {
            return this.subSwathIndex;
        }
    }


    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.snap.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.snap.framework.gpf.OperatorSpi#createOperator()
     * @see org.esa.snap.framework.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(RangeDopplerGeocodingOp.class);
        }
    }
}
