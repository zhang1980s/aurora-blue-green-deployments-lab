#### A Cluster

aws rds create-db-cluster --engine 'aurora-mysql' --engine-version '8.0.mysql_aurora.3.04.4' --engine-lifecycle-support 'open-source-rds-extended-support-disabled' --engine-mode 'provisioned' --db-cluster-identifier 'database-268-a' --vpc-security-group-ids 'sg-05801e0cae2f18b89' --port '3306' --db-cluster-parameter-group-name 'bggroup' --database-name 'lab_db' --master-username 'admin' --backup-retention-period '1' --db-subnet-group-name 'default' --enable-cloudwatch-logs-exports 'audit' 'error' 'general' 'iam-db-auth-error' 'instance' 'slowquery' --storage-type 'aurora-iopt1' --network-type 'IPV4' --master-user-password '2edc$TGB' --performance-insights-retention-period '7' --monitoring-role-arn 'arn:aws:iam::894855526703:role/rds-monitoring-role' --monitoring-interval '1' --database-insights-mode 'standard' --enable-performance-insights --region ap-southeast-1

aws rds create-db-instance --engine 'aurora-mysql' --engine-version '8.0.mysql_aurora.3.04.4' --engine-lifecycle-support 'open-source-rds-extended-support-disabled' --db-instance-identifier 'database-268-a-instance-1' --db-instance-class 'db.r6g.2xlarge' --db-subnet-group-name 'default' --db-cluster-identifier 'database-268-a' --region ap-southeast-1

./init-schema.sh --endpoint database-268-a.cluster-cfsctj42orch.ap-southeast-1.rds.amazonaws.com --database lab_db --username admin --password '2edc$TGB' --tables 12000

aws rds create-blue-green-deployment --blue-green-deployment-name 'bg-deployment-268-a' --source 'arn:aws:rds:ap-southeast-1:894855526703:cluster:database-268-a' --target-engine-version '8.0.mysql_aurora.3.10.2' --target-db-parameter-group-name 'default.aurora-mysql8.0' --target-db-cluster-parameter-group-name 'bggroup' --region ap-southeast-1


java -DLOG_LEVEL=DEBUG -jar target/workload-simulator.jar  \
--aurora-endpoint database-268-a.cluster-cfsctj42orch.ap-southeast-1.rds.amazonaws.com \
--database-name lab_db  \
--username admin \
--password '2edc$TGB' \
--write-workers 10 \
--write-rate 50 \
--read-workers 10 \
--read-rate 50 \
--connection-pool-size 150



#### B Cluster

aws rds create-db-cluster --engine 'aurora-mysql' --engine-version '8.0.mysql_aurora.3.04.4' --engine-lifecycle-support 'open-source-rds-extended-support-disabled' --engine-mode 'provisioned' --db-cluster-identifier 'database-268-b' --vpc-security-group-ids 'sg-05801e0cae2f18b89' --port '3306' --db-cluster-parameter-group-name 'bggroup' --database-name 'lab_db' --master-username 'admin' --backup-retention-period '1' --db-subnet-group-name 'default' --enable-cloudwatch-logs-exports 'audit' 'error' 'general' 'iam-db-auth-error' 'instance' 'slowquery' --storage-type 'aurora-iopt1' --network-type 'IPV4' --master-user-password '2edc$TGB' --performance-insights-retention-period '7' --monitoring-role-arn 'arn:aws:iam::894855526703:role/rds-monitoring-role' --monitoring-interval '1' --database-insights-mode 'standard' --enable-performance-insights --region ap-southeast-1

aws rds create-db-instance --engine 'aurora-mysql' --engine-version '8.0.mysql_aurora.3.04.4' --engine-lifecycle-support 'open-source-rds-extended-support-disabled' --db-instance-identifier 'database-268-b-instance-1' --db-instance-class 'db.r6g.2xlarge' --db-subnet-group-name 'default' --db-cluster-identifier 'database-268-b' --region ap-southeast-1

./init-schema.sh --endpoint database-268-b.cluster-cfsctj42orch.ap-southeast-1.rds.amazonaws.com --database lab_db --username admin --password '2edc$TGB' --tables 12000

aws rds create-blue-green-deployment --blue-green-deployment-name 'bg-deployment-268-b' --source 'arn:aws:rds:ap-southeast-1:894855526703:cluster:database-268-b' --target-engine-version '8.0.mysql_aurora.3.10.2' --target-db-parameter-group-name 'default.aurora-mysql8.0' --target-db-cluster-parameter-group-name 'bggroup' --region ap-southeast-1


java -DLOG_LEVEL=DEBUG -jar target/workload-simulator.jar  \
--aurora-endpoint database-268-b.cluster-cfsctj42orch.ap-southeast-1.rds.amazonaws.com \
--database-name lab_db  \
--username admin \
--password '2edc$TGB' \
--write-workers 10 \
--write-rate 50 \
--read-workers 10 \
--read-rate 50 \
--connection-pool-size 150