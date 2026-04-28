package com.benesquivelmusic.daw.core.dsp.regression;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container for {@link DspRegression} when it is applied multiple times to
 * the same test method (Java's repeating-annotation mechanism).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DspRegressionCases {
    DspRegression[] value();
}
