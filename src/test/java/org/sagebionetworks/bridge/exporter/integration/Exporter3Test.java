package org.sagebionetworks.bridge.exporter.integration;

import static org.sagebionetworks.bridge.rest.model.PerformanceOrder.SEQUENTIAL;
import static org.sagebionetworks.bridge.util.IntegTestUtils.TEST_APP_ID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseStsCredentialsProvider;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseNotFoundException;
import org.sagebionetworks.client.exceptions.SynapseResultNotReadyException;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValue;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;
import org.sagebionetworks.repo.model.sts.StsPermission;
import org.sagebionetworks.repo.model.table.QueryResultBundle;
import org.sagebionetworks.repo.model.table.RowSet;
import org.sagebionetworks.repo.model.table.SelectColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.config.Config;
import org.sagebionetworks.bridge.crypto.BcCmsEncryptor;
import org.sagebionetworks.bridge.crypto.PemUtils;
import org.sagebionetworks.bridge.rest.RestUtils;
import org.sagebionetworks.bridge.rest.api.AssessmentsApi;
import org.sagebionetworks.bridge.rest.api.ForAdminsApi;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.api.ForDevelopersApi;
import org.sagebionetworks.bridge.rest.api.ForWorkersApi;
import org.sagebionetworks.bridge.rest.api.ParticipantsApi;
import org.sagebionetworks.bridge.rest.api.SchedulesV2Api;
import org.sagebionetworks.bridge.rest.model.App;
import org.sagebionetworks.bridge.rest.model.Assessment;
import org.sagebionetworks.bridge.rest.model.AssessmentReference2;
import org.sagebionetworks.bridge.rest.model.Exporter3Configuration;
import org.sagebionetworks.bridge.rest.model.HealthDataRecordEx3;
import org.sagebionetworks.bridge.rest.model.Role;
import org.sagebionetworks.bridge.rest.model.Schedule2;
import org.sagebionetworks.bridge.rest.model.Session;
import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.TimeWindow;
import org.sagebionetworks.bridge.rest.model.Timeline;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.sagebionetworks.bridge.s3.S3Helper;
import org.sagebionetworks.bridge.user.TestUser;
import org.sagebionetworks.bridge.user.TestUserHelper;
import org.sagebionetworks.bridge.util.IntegTestUtils;

@SuppressWarnings({ "ConstantConditions", "deprecation", "OptionalGetWithoutIsPresent" })
public class Exporter3Test {
    private static final Logger LOG = LoggerFactory.getLogger(Exporter3Test.class);

    private static final String CONFIG_KEY_RAW_DATA_BUCKET = "health.data.bucket.raw";
    private static final String CONTENT_TYPE_TEXT_PLAIN = "text/plain";
    private static final String CUSTOM_METADATA_KEY = "custom-metadata-key";
    private static final String CUSTOM_METADATA_KEY_SANITIZED = "custom_metadata_key";
    private static final String CUSTOM_METADATA_VALUE = "custom-metadata-value";
    private static final byte[] UPLOAD_CONTENT = "This is the upload content".getBytes(StandardCharsets.UTF_8);

    private static TestUser adminDeveloperWorker;
    private static Exporter3Configuration ex3Config;
    private static String rawDataBucket;
    private static SynapseClient synapseClient;

    private TestUser user;
    private Schedule2 schedule;
    private Assessment assessment;

    @BeforeClass
    public static void beforeClass() throws Exception {
        Config config = TestUtils.loadConfig();
        rawDataBucket = config.get(CONFIG_KEY_RAW_DATA_BUCKET);

        // Set up SynapseClient.
        synapseClient = TestUtils.getSynapseClient(config);

        // Create admin account.
        adminDeveloperWorker = TestUserHelper.createAndSignInUser(Exporter3Test.class, false, Role.ADMIN,
                Role.DEVELOPER, Role.WORKER);

        // Wipe the Exporter 3 Config and re-create it.
        deleteEx3Resources();

        // Init Exporter 3.
        ForAdminsApi adminsApi = adminDeveloperWorker.getClient(ForAdminsApi.class);
        ex3Config = adminsApi.initExporter3().execute().body();
    }

    @BeforeMethod
    public void before() throws Exception {
        user = TestUserHelper.createAndSignInUser(Exporter3Test.class, true);
    }

    @AfterMethod
    public void after() throws Exception {
        if (user != null) {
            user.signOutAndDeleteUser();
        }
        TestUser admin = TestUserHelper.getSignedInAdmin();
        if (schedule != null) {
            SchedulesV2Api schedulesApi = admin.getClient(SchedulesV2Api.class);
            schedulesApi.deleteSchedule(schedule.getGuid()).execute();
        }
        if (assessment != null) {
            AssessmentsApi assessmentsApi = admin.getClient(AssessmentsApi.class);
            assessmentsApi.deleteAssessment(assessment.getGuid(), true).execute();
        }
    }

    @AfterClass
    public static void afterClass() throws Exception {
        // Clean up Synapse resources.
        deleteEx3Resources();

        if (adminDeveloperWorker != null) {
            adminDeveloperWorker.signOutAndDeleteUser();
        }
    }

    private static void deleteEx3Resources() throws IOException {
        ForAdminsApi adminsApi = adminDeveloperWorker.getClient(ForAdminsApi.class);
        App app = adminsApi.getUsersApp().execute().body();
        Exporter3Configuration ex3Config = app.getExporter3Configuration();
        if (ex3Config == null) {
            // Exporter 3 is not configured on this app. We can skip this step.
            return;
        }

        // Delete the project. This automatically deletes the folder too.
        String projectId = ex3Config.getProjectId();
        if (projectId != null) {
            try {
                synapseClient.deleteEntityById(projectId, true);
            } catch (SynapseException ex) {
                LOG.error("Error deleting project " + projectId, ex);
            }
        }

        // Delete the data access team.
        Long dataAccessTeamId = ex3Config.getDataAccessTeamId();
        if (dataAccessTeamId != null) {
            try {
                synapseClient.deleteTeam(String.valueOf(dataAccessTeamId));
            } catch (SynapseException ex) {
                LOG.error("Error deleting team " + dataAccessTeamId, ex);
            }
        }

        // Storage locations are idempotent, so no need to delete that.

        // Reset the Exporter 3 Config.
        app.setExporter3Configuration(null);
        app.setExporter3Enabled(false);
        adminsApi.updateUsersApp(app).execute();
    }

    // Note: There are other test cases, for example, if the participant uploads and then turns off sharing before the
    // upload is exported. This might happen if Synapse is down for maintenance.
    // This specific test will test no_sharing and redrives.
    @Test
    public void noSharingAndRedrives() throws Exception {
        // Participants created by TestUserHelper (UserAdminService) are set to no_sharing by default. No need to
        // change the participant here.

        // Upload file to Bridge.
        UploadInfo uploadInfo = uploadFile(UPLOAD_CONTENT, false);
        String filename = uploadInfo.filename;
        String uploadId = uploadInfo.uploadId;

        // Verify upload is NOT exported to Synapse.
        String rawFolderId = ex3Config.getRawDataFolderId();
        String todaysDateString = LocalDate.now(TestUtils.LOCAL_TIME_ZONE).toString();
        String exportedFilename = uploadId + '-' + filename;

        // Depending on the order that TestNG runs the tests, the parent folder might not have been created yet.
        String todayFolderId = getSynapseChildByName(rawFolderId, todaysDateString);
        String unsharedFileId = null;
        if (todayFolderId != null) {
            unsharedFileId = getSynapseChildByName(todayFolderId, exportedFilename);
        }
        assertNull(unsharedFileId);

        // Add a sharing status.
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        StudyParticipant participant = usersApi.getUsersParticipantRecord(false).execute().body();
        participant.setSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        usersApi.updateUsersParticipantRecord(participant).execute();

        // Redrive will look at participant's current sharing scope, which will cause the redriven upload to be
        // exported.
        usersApi.completeUploadSession(uploadId, true, true).execute();
        Thread.sleep(2000);
        todayFolderId = getSynapseChildByName(rawFolderId, todaysDateString);
        String sharedFileId = getSynapseChildByName(todayFolderId, exportedFilename);
        assertNotNull(sharedFileId);

        // Delete the file from Synapse and redrive again. We have a new file entity ID.
        synapseClient.deleteEntityById(sharedFileId, true);
        usersApi.completeUploadSession(uploadId, true, true).execute();
        Thread.sleep(2000);
        String redrivenFileId = getSynapseChildByName(todayFolderId, exportedFilename);
        assertNotNull(redrivenFileId);
        assertNotEquals(redrivenFileId, sharedFileId);

        // Redrive the upload a second time, but don't delete it first. Worker should silently handle this case.
        usersApi.completeUploadSession(uploadId, true, true).execute();
        Thread.sleep(2000);
        String redrivenFileId2 = getSynapseChildByName(todayFolderId, exportedFilename);
        assertNotNull(redrivenFileId2);
        assertEquals(redrivenFileId2, redrivenFileId);
    }

    @Test
    public void encryptedUpload() throws Exception {
        // Encrypt the upload content. Get the public key from Bridge.
        ForDevelopersApi developersApi = adminDeveloperWorker.getClient(ForDevelopersApi.class);
        String publicKey = developersApi.getAppPublicCsmKey().execute().body().getPublicKey();
        X509Certificate cert = PemUtils.loadCertificateFromPem(publicKey);
        BcCmsEncryptor encryptor = new BcCmsEncryptor(cert, null);
        byte[] encryptedUploadContent = encryptor.encrypt(UPLOAD_CONTENT);

        testUpload(encryptedUploadContent, true);
    }

    @Test
    public void unencryptedUpload() throws Exception {
        testUpload(UPLOAD_CONTENT, false);
    }
    
    @Test
    public void uploadWithScheduleContext() throws Exception {
        TestUser admin = TestUserHelper.getSignedInAdmin();
        AssessmentsApi assessmentsApi = admin.getClient(AssessmentsApi.class);
        SchedulesV2Api schedulesApi = admin.getClient(SchedulesV2Api.class);
        
        String assessmentId = getClass().getSimpleName() + "-" + RandomStringUtils.randomAlphabetic(10);
        
        assessment = new Assessment().title(assessmentId).osName("Universal").ownerId("sage-bionetworks")
                .identifier(assessmentId);
        assessment = assessmentsApi.createAssessment(assessment).execute().body();

        schedule = new Schedule2();
        schedule.setName("Test Schedule [Exporter3Test]");
        schedule.setDuration("P1W");

        Session session = new Session();
        session.setName("Once time task");
        session.addStartEventIdsItem("enrollment");
        session.setPerformanceOrder(SEQUENTIAL);

        AssessmentReference2 ref = new AssessmentReference2()
                .guid(assessment.getGuid()).appId(TEST_APP_ID)
                .title(assessmentId)
                .identifier(assessment.getIdentifier())
                .revision(assessment.getRevision().intValue());
        session.addAssessmentsItem(ref);
        session.addTimeWindowsItem(new TimeWindow().startTime("00:00").expiration("P1W"));
        schedule.addSessionsItem(session);

        schedule = schedulesApi.saveScheduleForStudy("study1", schedule).execute().body();
        
        Timeline timeline = schedulesApi.getTimelineForStudy("study1").execute().body();
        
        // Could also use the session instanceGuid, it doesn't matter. 
        String instanceGuid = timeline.getSchedule().get(0).getAssessments().get(0).getInstanceGuid();

        Map<String,String> userMetadata = new HashMap<>();
        userMetadata.put("instanceGuid", instanceGuid);

        Map<String,String> expectedMetadata = new HashMap<>();
        expectedMetadata.put("instanceGuid", instanceGuid);
        expectedMetadata.put("assessmentGuid", assessment.getGuid());
        expectedMetadata.put("assessmentId", assessment.getIdentifier());
        expectedMetadata.put("assessmentRevision", assessment.getRevision().toString());
        expectedMetadata.put("assessmentInstanceGuid", 
                timeline.getSchedule().get(0).getAssessments().get(0).getInstanceGuid());
        expectedMetadata.put("sessionInstanceGuid", timeline.getSchedule().get(0).getInstanceGuid());
        expectedMetadata.put("sessionGuid", timeline.getSchedule().get(0).getRefGuid());
        expectedMetadata.put("sessionInstanceStartDay", timeline.getSchedule().get(0).getStartDay().toString());
        expectedMetadata.put("sessionInstanceEndDay", timeline.getSchedule().get(0).getEndDay().toString());
        expectedMetadata.put("sessionStartEventId", "enrollment");
        expectedMetadata.put("timeWindowGuid", timeline.getSchedule().get(0).getTimeWindowGuid());
        expectedMetadata.put("scheduleGuid", schedule.getGuid());
        expectedMetadata.put("scheduleModifiedOn", schedule.getModifiedOn().toString());
        testUpload(UPLOAD_CONTENT, false, userMetadata, expectedMetadata);    
    }

    private void testUpload(byte[] content, boolean encrypted) throws Exception {
        testUpload(content, encrypted, ImmutableMap.of(CUSTOM_METADATA_KEY, CUSTOM_METADATA_VALUE),
                ImmutableMap.of(CUSTOM_METADATA_KEY_SANITIZED, CUSTOM_METADATA_VALUE));
    }
    
    private void testUpload(byte[] content, boolean encrypted, Map<String,String> userMetadata, Map<String,String> expectedMetadata) throws Exception {
        // Participants created by TestUserHelper (UserAdminService) are set to no_sharing by default. Enable sharing
        // so that the test can succeed.
        ParticipantsApi participantsApi = user.getClient(ParticipantsApi.class);
        StudyParticipant participant = participantsApi.getUsersParticipantRecord(false).execute().body();
        participant.setSharingScope(SharingScope.ALL_QUALIFIED_RESEARCHERS);
        participantsApi.updateUsersParticipantRecord(participant).execute();

        // Upload file to Bridge.
        UploadInfo uploadInfo = uploadFile(content, encrypted, userMetadata, expectedMetadata);
        String filename = uploadInfo.filename;
        String uploadId = uploadInfo.uploadId;

        // Verify Synapse file entity and annotations.
        String rawFolderId = ex3Config.getRawDataFolderId();
        String todaysDateString = LocalDate.now(TestUtils.LOCAL_TIME_ZONE).toString();
        String exportedFilename = uploadId + '-' + filename;

        // First, get the exported file.
        String todayFolderId = getSynapseChildByName(rawFolderId, todaysDateString);
        String exportedFileId = getSynapseChildByName(todayFolderId, exportedFilename);

        // Now verify the annotations.
        Annotations annotations = synapseClient.getAnnotationsV2(exportedFileId);
        Map<String, AnnotationsValue> annotationMap = annotations.getAnnotations();
        Map<String, String> flattenedAnnotationMap = new HashMap<>();
        for (Map.Entry<String, AnnotationsValue> annotationEntry : annotationMap.entrySet()) {
            String annotationKey = annotationEntry.getKey();
            AnnotationsValue annotationsValue = annotationEntry.getValue();
            if ("participantVersion".equals(annotationKey)) {
                // participantVersion is special. This needs to be joined with the ParticipantVersion table, so it's
                // a number.
                assertEquals(annotationsValue.getType(), AnnotationsValueType.LONG);
            } else {
                assertEquals(annotationsValue.getType(), AnnotationsValueType.STRING);
            }
            assertEquals(annotationsValue.getValue().size(), 1);
            flattenedAnnotationMap.put(annotationKey, annotationsValue.getValue().get(0));
        }
        verifyMetadata(flattenedAnnotationMap, uploadId, expectedMetadata);

        // Get the STS token and verify the file in S3.
        AWSCredentialsProvider awsCredentialsProvider = new SynapseStsCredentialsProvider(synapseClient, rawFolderId,
                StsPermission.read_only);
        AmazonS3Client s3Client = new AmazonS3Client(awsCredentialsProvider).withRegion(Regions.US_EAST_1);
        S3Helper s3Helper = new S3Helper();
        s3Helper.setS3Client(s3Client);

        String expectedS3Key = IntegTestUtils.TEST_APP_ID + '/' + todaysDateString + '/' + exportedFilename;
        byte[] s3Bytes = s3Helper.readS3FileAsBytes(rawDataBucket, expectedS3Key);
        assertEquals(s3Bytes, UPLOAD_CONTENT);

        ObjectMetadata s3Metadata = s3Helper.getObjectMetadata(rawDataBucket, expectedS3Key);
        assertEquals(s3Metadata.getSSEAlgorithm(), ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        assertEquals(s3Metadata.getContentType(), CONTENT_TYPE_TEXT_PLAIN);
        verifyMetadata(s3Metadata.getUserMetadata(), uploadId, expectedMetadata);

        // Verify the Participant Version table.
        String healthCode = flattenedAnnotationMap.get("healthCode");
        String participantVersionStr = flattenedAnnotationMap.get("participantVersion");
        String participantVersionTableId = ex3Config.getParticipantVersionTableId();
        String query = "SELECT * FROM " + participantVersionTableId + " WHERE healthCode='" + healthCode +
                "' and participantVersion=" + participantVersionStr;
        String queryJobId = synapseClient.queryTableEntityBundleAsyncStart(query, null, null,
                SynapseClient.QUERY_PARTMASK, participantVersionTableId);

        QueryResultBundle queryResultBundle = null;
        for (int loops = 0; loops < 5; loops++) {
            // Poll until result is ready.
            Thread.sleep(1000);
            try {
                queryResultBundle = synapseClient.queryTableEntityBundleAsyncGet(queryJobId,
                        participantVersionTableId);
            } catch (SynapseResultNotReadyException ex) {
                // Wait and try again.
            }
        }
        assertNotNull(queryResultBundle, "Timed out querying for participant version");

        RowSet queryRowSet = queryResultBundle.getQueryResult().getQueryResults();
        List<SelectColumn> columnList = queryRowSet.getHeaders();
        assertEquals(queryRowSet.getRows().size(), 1);
        List<String> rowValueList = queryRowSet.getRows().get(0).getValues();
        assertEquals(columnList.size(), rowValueList.size());
        Map<String, String> rowMap = new HashMap<>();
        for (int i = 0; i < columnList.size(); i++) {
            rowMap.put(columnList.get(i).getName(), rowValueList.get(i));
        }

        assertEquals(rowMap.get("healthCode"), healthCode);
        assertEquals(rowMap.get("participantVersion"), participantVersionStr);
        assertEquals(rowMap.get("sharingScope"), "all_qualified_researchers");

        // Timestamps are relatively recent. Because of clock skew on Jenkins, give a very generous time window of,
        // let's say, 1 hour.
        DateTime oneHourAgo = DateTime.now().minusHours(1);

        long participantCreatedOn = Long.parseLong(rowMap.get("createdOn"));
        assertTrue(participantCreatedOn > oneHourAgo.getMillis());

        long participantModifiedOn = Long.parseLong(rowMap.get("modifiedOn"));
        assertTrue(participantModifiedOn > oneHourAgo.getMillis());

        // Verify the record in Bridge.
        ForWorkersApi workersApi = adminDeveloperWorker.getClient(ForWorkersApi.class);
        HealthDataRecordEx3 record = workersApi.getRecordEx3(IntegTestUtils.TEST_APP_ID, uploadId).execute().body();
        assertTrue(record.isExported());
        assertTrue(record.getExportedOn().isAfter(oneHourAgo));
    }

    private static class UploadInfo {
        String filename;
        String uploadId;
    }
    
    private UploadInfo uploadFile(byte[] content, boolean encrypted) throws InterruptedException, IOException {
        return uploadFile(content, encrypted, ImmutableMap.of(CUSTOM_METADATA_KEY, CUSTOM_METADATA_VALUE),
                ImmutableMap.of(CUSTOM_METADATA_KEY_SANITIZED, CUSTOM_METADATA_VALUE));        
    }

    private UploadInfo uploadFile(byte[] content, boolean encrypted, Map<String, String> userMetadata,
            Map<String, String> expectedMetadata) throws InterruptedException, IOException {
        // Create a temp file so that we can use RestUtils.
        File file = File.createTempFile("text", ".txt");
        String filename = file.getName();
        Files.write(content, file);

        // Create upload request. We want to add custom metadata. Also, RestUtils defaults to application/zip. We want
        // to overwrite this.
        UploadRequest uploadRequest = RestUtils.makeUploadRequestForFile(file);
        uploadRequest.setContentType(CONTENT_TYPE_TEXT_PLAIN);
        for (Map.Entry<String,String> entry : userMetadata.entrySet()) {
            uploadRequest.putMetadataItem(entry.getKey(), entry.getValue());    
        }
        uploadRequest.setEncrypted(encrypted);
        uploadRequest.setZipped(false);

        // Upload.
        ForConsentedUsersApi usersApi = user.getClient(ForConsentedUsersApi.class);
        UploadSession session = usersApi.requestUploadSession(uploadRequest).execute().body();
        String uploadId = session.getId();
        RestUtils.uploadToS3(file, session.getUrl(), CONTENT_TYPE_TEXT_PLAIN);
        usersApi.completeUploadSession(session.getId(), true, false).execute();

        // Sleep for a bit to give the Worker Exporter time to finish.
        Thread.sleep(2000);

        UploadInfo uploadInfo = new UploadInfo();
        uploadInfo.filename = filename;
        uploadInfo.uploadId = uploadId;
        return uploadInfo;
    }

    private String getSynapseChildByName(String parentId, String childName) throws SynapseException {
        try {
            return synapseClient.lookupChild(parentId, childName);
        } catch (SynapseNotFoundException ex) {
            return null;
        }
    }

    private static void verifyMetadata(Map<String, String> metadataMap, String expectedRecordId, Map<String,String> expectedValues) {
        assertEquals(metadataMap.size(), 6 + expectedValues.size());
        assertTrue(metadataMap.containsKey("clientInfo"));
        assertTrue(metadataMap.containsKey("healthCode"));
        assertEquals(metadataMap.get("participantVersion"), "1");
        assertEquals(metadataMap.get("recordId"), expectedRecordId);
        for (String key : expectedValues.keySet()) {
            assertEquals(metadataMap.get(key), expectedValues.get(key), key + " has invalid value");
        }
        // Timestamps are relatively recent. Because of clock skew on Jenkins, give a very generous time window of,
        // let's say, 1 hour.
        DateTime oneHourAgo = DateTime.now().minusHours(1);
        DateTime exportedOn = DateTime.parse(metadataMap.get("exportedOn"));
        assertTrue(exportedOn.isAfter(oneHourAgo));

        DateTime uploadedOn = DateTime.parse(metadataMap.get("uploadedOn"));
        assertTrue(uploadedOn.isAfter(oneHourAgo));
    }
}
