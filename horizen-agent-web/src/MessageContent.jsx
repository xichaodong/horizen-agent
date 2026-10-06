import React from 'react';

const INLINE_PATTERN = /(https?:\/\/[^\s]+)|(`[^`]+`)|(\*\*[^*]+\*\*)/g;
const TABLE_DIVIDER_PATTERN = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/;

const trimTableRow = (line) =>
    line
        .trim()
        .replace(/^\|/, '')
        .replace(/\|$/, '')
        .split('|')
        .map((cell) => cell.trim());

const renderInline = (text, keyPrefix) => {
    const content = String(text || '');
    const nodes = [];
    let lastIndex = 0;
    let match;
    INLINE_PATTERN.lastIndex = 0;
    while ((match = INLINE_PATTERN.exec(content))) {
        if (match.index > lastIndex) {
            nodes.push(content.slice(lastIndex, match.index));
        }
        const value = match[0];
        const key = `${keyPrefix}_${match.index}`;
        if (value.startsWith('http')) {
            nodes.push(
                <a key={key} href={value} target="_blank" rel="noopener noreferrer">
                    {value}
                </a>
            );
        } else if (value.startsWith('`')) {
            nodes.push(<code key={key}>{value.slice(1, -1)}</code>);
        } else {
            nodes.push(<strong key={key}>{value.slice(2, -2)}</strong>);
        }
        lastIndex = match.index + value.length;
    }
    if (lastIndex < content.length) {
        nodes.push(content.slice(lastIndex));
    }
    return nodes;
};

const renderTable = (lines, startIndex) => {
    const headers = trimTableRow(lines[startIndex]);
    const rows = [];
    let cursor = startIndex + 2;
    while (cursor < lines.length && lines[cursor].includes('|') && lines[cursor].trim()) {
        rows.push(trimTableRow(lines[cursor]));
        cursor += 1;
    }
    return {
        cursor,
        node: (
            <div className="horizen-console__table-wrap" key={`table_${startIndex}`}>
                <table>
                    <thead>
                    <tr>
                        {headers.map((header, index) => (
                            <th key={`${header}_${index}`}>
                                {renderInline(header, `head_${index}`)}
                            </th>
                        ))}
                    </tr>
                    </thead>
                    <tbody>
                    {rows.map((row, rowIndex) => (
                        <tr key={`row_${rowIndex}`}>
                            {row.map((cell, cellIndex) => (
                                <td key={`cell_${rowIndex}_${cellIndex}`}>
                                    {renderInline(cell, `cell_${rowIndex}_${cellIndex}`)}
                                </td>
                            ))}
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>
        ),
    };
};

const MessageContent = ({content = ''}) => {
    const lines = String(content).replace(/\r\n/g, '\n').split('\n');
    const nodes = [];
    let cursor = 0;
    while (cursor < lines.length) {
        const line = lines[cursor];
        const trimmed = line.trim();
        if (!trimmed) {
            cursor += 1;
            continue;
        }
        if (trimmed.startsWith('```')) {
            const language = trimmed.slice(3).trim();
            const codeLines = [];
            cursor += 1;
            while (cursor < lines.length && !lines[cursor].trim().startsWith('```')) {
                codeLines.push(lines[cursor]);
                cursor += 1;
            }
            cursor += 1;
            nodes.push(
                <div className="horizen-console__code" key={`code_${cursor}`}>
                    {language ? <span>{language}</span> : null}
                    <pre>
                        <code>{codeLines.join('\n')}</code>
                    </pre>
                </div>
            );
            continue;
        }
        if (
            line.includes('|') &&
            cursor + 1 < lines.length &&
            TABLE_DIVIDER_PATTERN.test(lines[cursor + 1])
        ) {
            const table = renderTable(lines, cursor);
            nodes.push(table.node);
            cursor = table.cursor;
            continue;
        }
        const heading = trimmed.match(/^(#{1,4})\s+(.+)$/);
        if (heading) {
            const HeadingTag = `h${Math.min(heading[1].length + 2, 5)}`;
            nodes.push(
                <HeadingTag key={`heading_${cursor}`}>
                    {renderInline(heading[2], `heading_${cursor}`)}
                </HeadingTag>
            );
            cursor += 1;
            continue;
        }
        const unordered = trimmed.match(/^[-*]\s+(.+)$/);
        const ordered = trimmed.match(/^\d+[.)]\s+(.+)$/);
        if (unordered || ordered) {
            const ListTag = ordered ? 'ol' : 'ul';
            const itemPattern = ordered ? /^\d+[.)]\s+(.+)$/ : /^[-*]\s+(.+)$/;
            const items = [];
            while (cursor < lines.length) {
                const current = lines[cursor].trim();
                const itemMatch = current.match(itemPattern);
                if (!itemMatch) {
                    break;
                }
                items.push(itemMatch[1]);
                cursor += 1;
            }
            nodes.push(
                <ListTag key={`list_${cursor}`}>
                    {items.map((item, index) => (
                        <li key={`${item}_${index}`}>
                            {renderInline(item, `item_${cursor}_${index}`)}
                        </li>
                    ))}
                </ListTag>
            );
            continue;
        }
        nodes.push(<p key={`paragraph_${cursor}`}>{renderInline(line, `paragraph_${cursor}`)}</p>);
        cursor += 1;
    }
    return <div className="horizen-console__markdown">{nodes}</div>;
};

export default MessageContent;
