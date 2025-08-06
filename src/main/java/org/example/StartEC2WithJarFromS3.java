package org.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.Base64;

public class StartEC2WithJarFromS3 implements RequestHandler<Object, String> {

    private static final String AMI_ID = System.getenv("AMI_ID");
    private static final String INSTANCE_TYPE = System.getenv("INSTANCE_TYPE");
    private static final String S3_PATH = System.getenv("S3_PATH");
    private static final String S3_JAR_PATH = System.getenv("S3_JAR_PATH");
    private static final String KEY_NAME = System.getenv("KEY_NAME");
    private static final String SECURITY_GROUP_ID = System.getenv("SECURITY_GROUP_ID");
    private static final String SUBNET_ID = System.getenv("SUBNET_ID");
    private static final String IAM_INSTANCE_PROFILE_ARN = System.getenv("IAM_INSTANCE_PROFILE_ARN");

    @Override
    public String handleRequest(Object input, Context context) {
        String userData = "#!/bin/bash\n" +
                "exec > /home/ec2-user/user-data.log 2>&1\n" +
                "set -x\n" +
                "dnf update -y\n" +
                "dnf install -y java-17-amazon-corretto-headless\n" +
                "runuser -l ec2-user -c 'cd /home/ec2-user && aws s3 cp --region ap-south-1 " + S3_JAR_PATH + " app.jar && nohup java -jar app.jar --spring.profiles.active=prod > app.log 2>&1 &' \n" +
                "\n" +
                "# Create shutdown script\n" +
                "cat << 'EOF' > /home/ec2-user/upload-log.sh\n" +
                "#!/bin/bash\n" +
                "DATE=$(date +%Y%m%d-%H%M%S)\n" +
                "BUCKET_PATH=" + S3_PATH + "/$DATE\n" +
                "aws s3 cp --region ap-south-1 /home/ec2-user/app.log $BUCKET_PATH/app.log\n" +
                "aws s3 cp --region ap-south-1 /home/ec2-user/data.csv $BUCKET_PATH/data.csv\n" +
                "EOF\n" +
                "chmod +x /home/ec2-user/upload-log.sh\n" +
                "\n" +
                "# Register systemd shutdown service\n" +
                "cat << 'EOF' > /etc/systemd/system/upload-log.service\n" +
                "[Unit]\n" +
                "Description=Upload app.log to S3 on shutdown\n" +
                "DefaultDependencies=no\n" +
                "Before=shutdown.target\n" +
                "\n" +
                "[Service]\n" +
                "Type=oneshot\n" +
                "ExecStart=/home/ec2-user/upload-log.sh\n" +
                "RemainAfterExit=true\n" +
                "\n" +
                "[Install]\n" +
                "WantedBy=shutdown.target\n" +
                "EOF\n" +
                "systemctl enable upload-log.service\n";

        String base64UserData = Base64.getEncoder().encodeToString(userData.getBytes());

        try (Ec2Client ec2 = Ec2Client.builder().region(Region.AP_SOUTH_1).build()) {
            RunInstancesResponse response = ec2.runInstances(RunInstancesRequest.builder()
                    .imageId(AMI_ID)
                    .instanceType(INSTANCE_TYPE)
                    .keyName(KEY_NAME)
                    .securityGroupIds(SECURITY_GROUP_ID)
                    .subnetId(SUBNET_ID)
                    .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                            .arn(IAM_INSTANCE_PROFILE_ARN).build())
                    .userData(base64UserData)
                    .minCount(1)
                    .maxCount(1)
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.INSTANCE)
                            .tags(Tag.builder().key("CreatedFor").value("Kite").build(),
                                    Tag.builder().key("Name").value("ec2-kite").build())
                            .build())
                    .build());

            String instanceId = response.instances().get(0).instanceId();
            return "Started EC2 instance with ID: " + instanceId;
        }
    }
}