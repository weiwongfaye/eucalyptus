package com.eucalyptus.bootstrap;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import edu.ucsb.eucalyptus.msgs.BaseMessage;

@Target({ ElementType.TYPE, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface Handles {//TODO:GRZE: this ats feeling lonely so far away from rest of compId configuration
  Class<? extends BaseMessage>[] value();
}
