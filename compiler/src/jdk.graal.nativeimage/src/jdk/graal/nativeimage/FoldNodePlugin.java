package jdk.graal.nativeimage;

/**
 * A marker interface for generated classes that implement compile-time evaluation (i.e. folding) of
 * certain method calls where the folding must be deferred to libgraal runtime.
 * 
 * @since 25
 */
public interface FoldNodePlugin {
}
