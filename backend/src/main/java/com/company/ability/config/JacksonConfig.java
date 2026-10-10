package com.company.ability.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * Jackson 配置类
 * 统一配置日期时间格式化
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIME_PATTERN = "HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            // LocalDateTime 序列化器和反序列化器
            builder.serializers(
                new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN))
            );
            builder.deserializers(
                new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN))
            );

            // LocalDate 序列化器和反序列化器
            builder.serializers(
                new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_PATTERN))
            );
            builder.deserializers(
                new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE_PATTERN))
            );

            // LocalTime 序列化器和反序列化器
            builder.serializers(
                new LocalTimeSerializer(DateTimeFormatter.ofPattern(TIME_PATTERN))
            );
            builder.deserializers(
                new LocalTimeDeserializer(DateTimeFormatter.ofPattern(TIME_PATTERN))
            );
        };
    }
}
