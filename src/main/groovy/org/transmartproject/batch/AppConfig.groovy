package org.transmartproject.batch

import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.configuration.annotation.BatchConfigurer
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.launch.support.SimpleJobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean
import org.springframework.batch.core.scope.JobScope
import org.springframework.batch.core.scope.StepScope
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.support.lob.OracleLobHandler
import org.springframework.jdbc.support.nativejdbc.CommonsDbcpNativeJdbcExtractor
import org.springframework.transaction.PlatformTransactionManager
import org.transmartproject.batch.batchartifacts.BetterExitMessageJobExecutionListener
import org.transmartproject.batch.batchartifacts.DefaultJobIncrementer
import org.transmartproject.batch.db.DbConfig
import org.transmartproject.batch.db.PerDbTypeRunner
import org.transmartproject.batch.support.ExpressionResolver

import javax.sql.DataSource

/**
 * Additional batch configurations
 */
@Configuration
@Import(DbConfig)
@EnableBatchProcessing
class AppConfig {

    @Bean
    @Lazy
    // so that the singleton is not eagerly initialized, which would fail
    // in tests where we're just testing the composition of the
    // application context and don't have a real data source behind
    BatchConfigurer batchConfigurer(DataSource dataSource,
                                    PerDbTypeRunner perDbTypeRunner) {
        // extending DefaultBatchConfigurer ends up not being practical due
        // to its use of private fields
        new BatchConfigurer() {
            final PlatformTransactionManager transactionManager =
                    new DataSourceTransactionManager(dataSource)

            final JobRepository jobRepository = {
                JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean()
                perDbTypeRunner.run([
                        postgresql: { -> },
                        oracle    : { ->
                            factory.isolationLevelForCreate = 'ISOLATION_READ_COMMITTED'
                            OracleLobHandler lobHandler = new OracleLobHandler()
                            lobHandler.nativeJdbcExtractor = new CommonsDbcpNativeJdbcExtractor()
                            factory.lobHandler = lobHandler
                            factory.maxVarCharLength = 2500 / 2 // oracle col length definitions in bytes
                        },
                ])
                factory.transactionManager = transactionManager
                factory.dataSource = dataSource
                factory.afterPropertiesSet()
                factory.object
            }()

            final JobLauncher jobLauncher = {
                SimpleJobLauncher jobLauncher = new SimpleJobLauncher()
                jobLauncher.jobRepository = jobRepository
                jobLauncher.afterPropertiesSet()
                jobLauncher
            }()

            final JobExplorer jobExplorer = {
                JobExplorerFactoryBean jobExplorerFactoryBean = new JobExplorerFactoryBean()
                jobExplorerFactoryBean.dataSource = dataSource
                jobExplorerFactoryBean.afterPropertiesSet()
                jobExplorerFactoryBean.object
            }()
        }
    }

    @Bean
    BeanFactoryPostProcessor customizeJob() {
        { ConfigurableListableBeanFactory beanFactory ->
            def beanNames = beanFactory.getBeanNamesForType(Job)
            beanNames.each { beanName ->
                beanFactory.getBeanDefinition(beanName)
                        .propertyValues.with {
                    addPropertyValue('jobExecutionListeners',
                            [new BetterExitMessageJobExecutionListener()] as JobExecutionListener[])
                    addPropertyValue('jobParametersIncrementer', new DefaultJobIncrementer())
                }
            }
        } as BeanFactoryPostProcessor
    }

    @Bean
    ExpressionResolver expressionResolver() {
        new ExpressionResolver()
    }

    /* override beans to fix warnings due to them not being static in
     * org.springframework.batch.core.configuration.annotation.AbstractBatchConfiguration.ScopeConfiguration */

    @Bean
    static StepScope stepScope() {
        new StepScope().with {
            autoProxy = false
            it
        }
    }

    @Bean
    static JobScope jobScope() {
        new JobScope().with {
            autoProxy = false
            it
        }
    }
}