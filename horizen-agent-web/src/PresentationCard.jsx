import React from 'react';

const text = (value) => (value === null || value === undefined ? '' : String(value));
const list = (value) => (Array.isArray(value) ? value : []);

const severityClass = (value) => {
    const normalized = text(value).toLowerCase();
    if (['p0', 'high', 'critical', 'error', 'danger'].includes(normalized)) return 'is-danger';
    if (['p1', 'medium-high', 'warning', 'warn'].includes(normalized)) return 'is-warning';
    if (['good', 'positive', 'success'].includes(normalized)) return 'is-positive';
    return 'is-neutral';
};

const Metrics = ({values}) =>
    list(values).length ? (
        <div className="horizen-console__presentation-metrics">
            {list(values).map((metric, index) => (
                <div
                    className={`horizen-console__presentation-metric ${severityClass(metric?.status)}`}
                    key={metric?.id || `${metric?.label || 'metric'}-${index}`}
                >
                    <span>{text(metric?.label || metric?.name)}</span>
                    <strong>{text(metric?.value)}</strong>
                    {metric?.change !== undefined && <small>{text(metric.change)}</small>}
                </div>
            ))}
        </div>
    ) : null;

const Chips = ({values}) =>
    list(values).length ? (
        <div className="horizen-console__presentation-chips">
            {list(values).map((chip, index) => {
                const value = typeof chip === 'object' ? chip : {label: chip};
                return (
                    <span className={severityClass(value.status)} key={value.id || index}>
                        {text(value.label || value.name)}
                        {value.value !== undefined ? ` ${text(value.value)}` : ''}
                    </span>
                );
            })}
        </div>
    ) : null;

const KnownCard = ({type, data, onOpenArtifact}) => {
    if (type === 'external_report' || type === 'external_page') {
        let url;
        try {
            const parsed = new URL(text(data.url));
            if (
                ['http:', 'https:'].includes(parsed.protocol) &&
                !parsed.username &&
                !parsed.password
            ) {
                url = parsed.href;
            }
        } catch {
            /* 无效链接仅作为文本展示，不执行。 */
        }
        return (
            <div className="horizen-console__presentation-artifact">
                <span>
                    <strong>{text(data.title || '外部内容')}</strong>
                    <small>{type === 'external_report' ? 'Markdown 报告' : '网页'}</small>
                </span>
                {url ? (
                    <a href={url} target="_blank" rel="noopener noreferrer">
                        {type === 'external_report' ? '查看报告' : '打开网页'}
                    </a>
                ) : (
                    <small>链接不可用</small>
                )}
            </div>
        );
    }
    if (type === 'artifact_card') {
        const label =
            data.variant === 'report'
                ? '查看报告'
                : data.variant === 'image'
                    ? '查看图片'
                    : '打开文件';
        return (
            <button
                type="button"
                className="horizen-console__presentation-artifact"
                onClick={() => onOpenArtifact?.(data)}
            >
                <span>
                    <strong>{text(data.title || data.artifactId || '产物')}</strong>
                    <small>{text(data.mediaType || '文件产物')}</small>
                </span>
                <em>{label}</em>
            </button>
        );
    }
    if (type === 'conclusion')
        return (
            <>
                <h3>{text(data.title || data.conclusion || '结论')}</h3>
                {data.summary && <p>{text(data.summary)}</p>}
                {list(data.lines).map((line, index) => (
                    <p key={index}>{text(line)}</p>
                ))}
                <Chips values={data.keywords}/>
            </>
        );
    if (type === 'metric_overview')
        return (
            <>
                <h3>{text(data.title || '核心数据概览')}</h3>
                {data.summary && <p>{text(data.summary)}</p>}
                <Metrics values={data.metrics}/>
            </>
        );
    if (type === 'metric')
        return (
            <>
                <h3>{text(data.title || '指标')}</h3>
                <Chips values={data.metrics || data.chips}/>
                {data.summary && <p>{text(data.summary)}</p>}
            </>
        );
    if (type === 'score_structure')
        return (
            <>
                <div className="horizen-console__presentation-score">
                    <strong>{text(data.score)}</strong>
                    <span>{text(data.scoreLabel || '综合评分')}</span>
                </div>
                <h3>{text(data.title || '评分与结构')}</h3>
                <Metrics values={data.items || data.dimensions}/>
                <Chips values={data.tags}/>
            </>
        );
    if (type === 'issue')
        return (
            <>
                <div className="horizen-console__presentation-heading">
                    <h3>{text(data.title || '发现问题')}</h3>
                    {data.severity && <span>{text(data.severity)}</span>}
                </div>
                <Chips values={data.metrics}/>
                {data.summary && <p>{text(data.summary)}</p>}
            </>
        );
    if (type === 'action') {
        const action = data.action && typeof data.action === 'object' ? data.action : {};
        return (
            <>
                <div className="horizen-console__presentation-heading">
                    <h3>{text(data.title || '建议行动')}</h3>
                    {data.category && <span>{text(data.category)}</span>}
                </div>
                {data.reason && <p>{text(data.reason)}</p>}
                {(data.actionLabel || action.label) && (
                    <span className="horizen-console__presentation-action">
                        {text(data.actionLabel || action.label)}
                    </span>
                )}
            </>
        );
    }
    return null;
};

const KNOWN_TYPES = new Set([
    'artifact_card',
    'external_report',
    'external_page',
    'conclusion',
    'metric_overview',
    'metric',
    'score_structure',
    'issue',
    'action',
]);

export default function PresentationCard({presentation, onOpenArtifact}) {
    const type = text(presentation?.type);
    const data =
        presentation?.data && typeof presentation.data === 'object' ? presentation.data : {};
    const known = KNOWN_TYPES.has(type);
    return (
        <article
            className={`horizen-console__presentation horizen-console__presentation--${type || 'unknown'} ${severityClass(data.severity || data.status)}`}
        >
            {known ? (
                <KnownCard type={type} data={data} onOpenArtifact={onOpenArtifact}/>
            ) : (
                <>
                    <h3>{text(data.title || type || '结构化结果')}</h3>
                    {data.summary ? (
                        <p>{text(data.summary)}</p>
                    ) : (
                        <pre>{JSON.stringify(data, null, 2)}</pre>
                    )}
                    <small>当前客户端暂不支持此卡型</small>
                </>
            )}
        </article>
    );
}
