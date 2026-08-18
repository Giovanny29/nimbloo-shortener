package com.nimbloo.shortener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.nimbloo.shortener.config.AwsResourceInitializer;
import com.nimbloo.shortener.consumer.SqsClickConsumer;

@SpringBootTest
class ShortenerApplicationTests {

	@MockBean
	private SqsClickConsumer sqsClickConsumer;

	@MockBean
	private AwsResourceInitializer awsResourceInitializer;

	@Test
	void contextLoads() {
	}

}