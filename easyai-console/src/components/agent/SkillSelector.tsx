import React from 'react';
import { useAgentStore } from '@/services/stores/agent-store';
import { BookOpen } from 'lucide-react';

interface SkillSelectorProps {
  selectedSkills: string[];
  onChange: (skills: string[]) => void;
  disabled?: boolean;
}

export const SkillSelector: React.FC<SkillSelectorProps> = ({ selectedSkills, onChange, disabled }) => {
  const { skills } = useAgentStore();

  const toggleSkill = (skillName: string) => {
    if (selectedSkills.includes(skillName)) {
      onChange(selectedSkills.filter(s => s !== skillName));
    } else {
      onChange([...selectedSkills, skillName]);
    }
  };

  return (
    <div className="space-y-3">
      <div>
        <label className="text-sm font-medium">Skills</label>
        <p className="text-xs text-muted-foreground mt-1">
          Select skills this agent can load. Leave empty to disable all skills.
        </p>
      </div>

      {skills.length === 0 ? (
        <p className="text-xs text-muted-foreground italic">No skills available.</p>
      ) : (
        <div className="space-y-2">
          {skills.map((skill) => {
            const isSelected = selectedSkills.includes(skill.name);
            return (
              <label
                key={skill.name}
                className={`flex items-center gap-3 p-3 rounded-lg border transition-colors ${
                  disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'
                } ${
                  isSelected
                    ? 'border-primary bg-primary/5'
                    : disabled ? 'border-border' : 'border-border hover:border-muted-foreground'
                }`}
              >
                <input
                  type="checkbox"
                  checked={isSelected}
                  onChange={() => toggleSkill(skill.name)}
                  disabled={disabled}
                  className="w-4 h-4 rounded border-input text-primary focus:ring-primary"
                />
                <span className="flex-shrink-0 text-muted-foreground">
                  <BookOpen className="w-4 h-4" />
                </span>
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium">{skill.name}</div>
                  {skill.description && (
                    <div className="text-xs text-muted-foreground">{skill.description}</div>
                  )}
                  {skill.tags.length > 0 && (
                    <div className="flex gap-1 mt-1">
                      {skill.tags.map(tag => (
                        <span key={tag} className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
};
