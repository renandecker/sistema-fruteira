package io.smallrye.config;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME) public @interface ConfigMapping { String prefix() default ""; }
