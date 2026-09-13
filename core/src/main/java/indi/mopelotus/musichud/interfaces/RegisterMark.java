package indi.mopelotus.musichud.interfaces;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marker consumed by the registration processor and retained in source only. */
@Target({ElementType.TYPE, ElementType.TYPE_USE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.SOURCE)
public @interface RegisterMark {
}
