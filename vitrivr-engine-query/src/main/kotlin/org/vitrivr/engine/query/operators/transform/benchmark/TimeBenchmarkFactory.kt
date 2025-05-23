package org.vitrivr.engine.query.operators.transform.benchmark

import org.vitrivr.engine.core.context.Context
import org.vitrivr.engine.core.context.QueryContext
import org.vitrivr.engine.core.model.retrievable.Retrievable
import org.vitrivr.engine.core.operators.Operator
import org.vitrivr.engine.core.operators.general.TransformerFactory
import kotlin.io.path.Path

class TimeBenchmarkFactory() : TransformerFactory {
    override fun newTransformer(name: String, input: Operator<out Retrievable>, context: Context): TimeBenchmark {
        require(context is QueryContext)
        val logfilePath = Path(context[name, "logfile"]?.toString() ?: "benchmark.log")
        val prettyName = context[name, "pretty"]?.toString() ?: name
        return TimeBenchmark(input, logfilePath, prettyName, name)
    }
}
