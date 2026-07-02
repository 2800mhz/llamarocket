package info.openrocket.core.ai;

/**
 * Public agent entry point for the LlamaRocket product.
 * QwenAgent remains as a compatibility base for documents and integrations created by
 * early prototypes; new code should depend on this class.
 */
public class LlamaRocketAgent extends QwenAgent {
	public LlamaRocketAgent(String modelName, String ollamaUrl) {
		super(modelName, ollamaUrl);
	}
}
