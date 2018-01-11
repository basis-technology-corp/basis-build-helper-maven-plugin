assert(new File("target/it/rosapi-copier-skip/target/bundles").listFiles().length == 2)
assert(new File("target/it/rosapi-copier-skip/target/bundles/commons-io-commons-io-2.4.jar").exists())
assert(new File("target/it/rosapi-copier-skip/target/bundles/bundles.xml").exists())