package org.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.Base64;

public class StartEC2WithJarFromS3 implements RequestHandler<Object, String> {

    private static final String AMI_ID = System.getenv("AMI_ID");
    private static final String INSTANCE_TYPE = System.getenv("INSTANCE_TYPE");
    private static final String S3_JAR_PATH = System.getenv("S3_JAR_PATH");
    private static final String KEY_NAME = System.getenv("KEY_NAME");
    private static final String SECURITY_GROUP_ID = System.getenv("SECURITY_GROUP_ID");
    private static final String SUBNET_ID = System.getenv("SUBNET_ID");
    private static final String IAM_INSTANCE_PROFILE_ARN = System.getenv("IAM_INSTANCE_PROFILE_ARN");

    @Override
    public String handleRequest(Object input, Context context) {
        String userData = "#!/bin/bash\n" +
                "exec > /home/ubuntu/user-data.log 2>&1\n" +
                "set -x\n" +
                "sudo apt-get update\n" +
                "sudo apt-get install -y unzip curl openjdk-17-jdk mysql-client\n" +
                "cd /tmp\n" +
                "curl \"https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip\" -o \"awscliv2.zip\"\n" +
                "unzip awscliv2.zip\n" +
                "sudo ./aws/install\n" +
                "sudo -u ubuntu bash -c 'cd /home/ubuntu && aws s3 cp " + S3_JAR_PATH + " app.jar && nohup java -jar app.jar --spring.profiles.active=prod > app.log 2>&1 &'\n";
        
        String base64UserData = Base64.getEncoder().encodeToString(userData.getBytes());

        try (Ec2Client ec2 = Ec2Client.create()) {
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